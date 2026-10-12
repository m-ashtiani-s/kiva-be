package com.kiva.infrastructure.payment

import com.kiva.application.port.GatewayCallback
import com.kiva.application.port.GatewayFailure
import com.kiva.application.port.GatewayInquiry
import com.kiva.application.port.GatewayPaymentRequest
import com.kiva.application.port.GatewayReceipt
import com.kiva.application.port.GatewayRedirect
import com.kiva.application.port.PaymentGateway
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import kotlinx.coroutines.delay
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.security.SecureRandom
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pretend Zarinpal for development and tests (`kiva.payment.provider = fake`, roadmap section 10.4). It stands in for
 * ZARINPAL and walks the same path a real payment does: the shopper is sent to a page on this server
 * (`/dev/fake-gateway/{authority}`) with «پرداخت موفق» and «انصراف», which comes back to the same callback Zarinpal
 * would use. Its sessions live in memory only.
 *
 * Two more jobs in tests:
 * - it can be told not to answer, to refuse, or to be slow ([mode], [delay]), or not to answer one kind of call only
 *   ([unreachable]), and it counts its calls ([callsOf]);
 * - **every call checks that no database transaction is open**, and throws if one is: a gateway call inside a
 *   transaction holds a connection for as long as the gateway takes, so whoever puts one there fails the build. It is
 *   an adapter, so it may ask Exposed directly; the application layer never knows about Exposed.
 */
class FakePaymentGateway(private val config: PaymentConfig, private val random: SecureRandom = SecureRandom()) : PaymentGateway {

    private val logger = getLogger()

    override val code: PaymentGatewayCode = PaymentGatewayCode.ZARINPAL

    /** How it answers, for tests. */
    enum class Mode {
        NORMAL,

        /** No answer: every call is Unreachable. */
        UNREACHABLE,

        /** Our request is refused: every call is Rejected(-10), Zarinpal's «wrong merchant». */
        REJECTING,
    }

    @Volatile
    var mode: Mode = Mode.NORMAL

    /** How long each call takes, for tests about slow gateways. */
    @Volatile
    var delay: Duration = Duration.ZERO

    /** The kinds of call ([REQUEST], [VERIFY], [INQUIRE], [REVERSE]) that get no answer, whatever the [mode]. */
    val unreachable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Where a session stands. */
    enum class State { IN_BANK, PAID, CANCELLED, VERIFIED, REVERSED }

    /** One session, as the pretend bank keeps it. */
    class Session(val authority: GatewayAuthority, val orderCode: OrderCode, val amount: Money, val refId: GatewayRefId) {
        @Volatile
        var state: State = State.IN_BANK
            internal set
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    init {
        logger.warn("Payment gateway is 'fake': a page on this server plays the bank and no money moves. Development only.")
    }

    // ============ the gateway ============

    override suspend fun requestPayment(request: GatewayPaymentRequest): Result<GatewayRedirect, GatewayFailure> = call(REQUEST) {
        val authority = GatewayAuthority.constructFromPersisted("FAKE-" + HexFormat.of().withUpperCase().formatHex(ByteArray(16).also(random::nextBytes)))
        val refId = GatewayRefId.constructFromPersisted((100_000_000 + random.nextInt(900_000_000)).toString())
        sessions[authority.value] = Session(authority, request.orderCode, request.amount, refId)
        logger.info("FakePaymentGateway.requestPayment paymentId=${request.paymentId.value} order=${request.orderCode.value} amount=${request.amount.toman}")
        Result.success(GatewayRedirect(authority, PaymentRedirect("${config.callbackBaseUrl}/dev/fake-gateway/${authority.value}", PaymentRedirect.Method.GET)))
    }

    /** Zarinpal's own parameters: `Authority`, and `Status` OK or NOK. */
    override fun readCallback(params: Map<String, String>): GatewayCallback? {
        val authority = GatewayAuthority.of(params[AUTHORITY]) ?: return null
        return when (params[STATUS]) {
            OK -> GatewayCallback(authority, paid = true)
            NOK -> GatewayCallback(authority, paid = false)
            else -> null
        }
    }

    override suspend fun verify(authority: GatewayAuthority, amount: Money): Result<GatewayReceipt, GatewayFailure> = call(VERIFY) {
        val session = sessions[authority.value] ?: return@call Result.failure(GatewayFailure.Declined(NOT_FOUND))
        when (session.state) {
            State.VERIFIED -> Result.success(receiptOf(session, alreadyConfirmed = true))
            State.PAID ->
                if (amount != session.amount) {
                    Result.failure(GatewayFailure.AmountMismatch)
                } else {
                    session.state = State.VERIFIED
                    Result.success(receiptOf(session, alreadyConfirmed = false))
                }
            State.IN_BANK, State.CANCELLED, State.REVERSED -> Result.failure(GatewayFailure.Declined(NOT_PAID))
        }
    }

    override suspend fun inquire(authority: GatewayAuthority): Result<GatewayInquiry, GatewayFailure> = call(INQUIRE) {
        val session = sessions[authority.value] ?: return@call Result.failure(GatewayFailure.Declined(NOT_FOUND))
        Result.success(
            when (session.state) {
                State.IN_BANK -> GatewayInquiry.IN_BANK
                State.PAID -> GatewayInquiry.PAID
                State.VERIFIED -> GatewayInquiry.VERIFIED
                State.CANCELLED -> GatewayInquiry.FAILED
                State.REVERSED -> GatewayInquiry.REVERSED
            },
        )
    }

    /** Only a confirmed session can be given back, as with Zarinpal (-61 otherwise). */
    override suspend fun reverse(authority: GatewayAuthority): Result<Unit, GatewayFailure> = call(REVERSE) {
        val session = sessions[authority.value] ?: return@call Result.failure(GatewayFailure.Declined(NOT_FOUND))
        synchronized(session) {
            if (session.state != State.VERIFIED) return@call Result.failure(GatewayFailure.Declined(NOT_CONFIRMED))
            session.state = State.REVERSED
        }
        logger.info("FakePaymentGateway.reverse order=${session.orderCode.value}")
        Result.success(Unit)
    }

    // ============ the pretend bank's page ============

    fun session(authority: String): Session? = sessions[authority]

    /** «پرداخت موفق»: the shopper paid. Pressing it again is fine; after «انصراف» it is too late. */
    fun pay(authority: String): Boolean = press(authority, State.PAID)

    /** «انصراف»: the shopper gave up. Pressing it again is fine; after paying it is too late. */
    fun cancel(authority: String): Boolean = press(authority, State.CANCELLED)

    /** Where the page sends the shopper back: the callback Zarinpal would call. */
    fun callbackUrl(authority: String, paid: Boolean): String = "${config.callbackUrlOf(code)}?$AUTHORITY=$authority&$STATUS=${if (paid) OK else NOK}"

    // ============ for tests ============

    /** How many times [kind] (`requestPayment`, `verify`, `inquire`) was called. */
    fun callsOf(kind: String): Int = calls[kind]?.get() ?: 0

    // ============ inside ============

    private fun press(authority: String, to: State): Boolean {
        val session = sessions[authority] ?: return false
        synchronized(session) {
            if (session.state == State.IN_BANK) session.state = to
            return session.state == to || (to == State.PAID && session.state == State.VERIFIED)
        }
    }

    private fun receiptOf(session: Session, alreadyConfirmed: Boolean) =
        GatewayReceipt(session.refId, CardMask.constructFromPersisted("6037-99**-****-1234"), alreadyConfirmed)

    private suspend fun <T> call(kind: String, block: () -> Result<T, GatewayFailure>): Result<T, GatewayFailure> {
        check(TransactionManager.currentOrNull() == null) {
            "The payment gateway ($kind) was called inside a database transaction. A gateway call must never hold a connection: " +
                "end the transaction, call the gateway, then open a new one (roadmap section 10.5)."
        }
        calls.computeIfAbsent(kind) { AtomicInteger() }.incrementAndGet()
        if (!delay.isZero) delay(delay.toMillis())
        if (kind in unreachable) return Result.failure(GatewayFailure.Unreachable)
        return when (mode) {
            Mode.UNREACHABLE -> Result.failure(GatewayFailure.Unreachable)
            Mode.REJECTING -> Result.failure(GatewayFailure.Rejected(WRONG_MERCHANT))
            Mode.NORMAL -> block()
        }
    }

    companion object {
        const val REQUEST = "requestPayment"
        const val VERIFY = "verify"
        const val INQUIRE = "inquire"
        const val REVERSE = "reverse"

        private const val AUTHORITY = "Authority"
        private const val STATUS = "Status"
        private const val OK = "OK"
        private const val NOK = "NOK"

        // Zarinpal's codes, so the fake fails the way the real one does.
        private const val WRONG_MERCHANT = -10
        private const val NOT_PAID = -51
        private const val NOT_FOUND = -55
        private const val NOT_CONFIRMED = -61
    }
}
