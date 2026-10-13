package com.kiva.domain.aggregate.order.entity

import com.kiva.common.event.EventSourceEntity
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.EXPIRED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PAYMENT_FAILED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PENDING_PAYMENT
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PROCESSING
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.RESERVED
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates

/**
 * A customer's order: what they bought and at what price, where it goes, how it ships, and whether it is paid.
 *
 * Event-sourced: a long life with a timeline (`OrderDetail.timeline` is its events), and a financial record. Everything
 * is copied when it is placed ([OrderPlaced]), so a later price, product name or saved address never changes it.
 *
 * Rules it keeps by itself:
 * - it has at least one line, and all colors of a product together are at most that product's limit per order;
 * - an order with nothing to pay is paid the moment it is placed, so an unpaid order always has something to pay;
 * - it is paid only while unpaid, exactly its payable, once;
 * - a failed payment attempt is recorded once, and only while the order is unpaid;
 * - it expires only while unpaid and not before its [holdUntil]; expiring twice changes nothing.
 *
 * Its stock is not here: the Inventory of each color holds it for the order, by its [code].
 */
class Order private constructor() : EventSourceEntity<OrderDomainEvent>() {

    // Set by apply(). Kotlin doesn't allow lateinit on value classes, so those use Delegates.notNull().
    var id: OrderId by Delegates.notNull()
        private set
    var code: OrderCode by Delegates.notNull()
        private set
    var userId: UserId by Delegates.notNull()
        private set
    var status: OrderStatus = PENDING_PAYMENT
        private set
    lateinit var address: AddressSnapshot
        private set
    lateinit var messenger: PreShipmentMessenger
        private set
    lateinit var shippingMethod: ShippingMethodCode
        private set
    lateinit var totals: OrderTotals
        private set

    /** «رزرو ۴ روزه» was asked for, for an order of its own (round 2). */
    var reserveRequested: Boolean = false
        private set

    /** The reservation group it ships with: one it joined when placed, or one it started or joined when paid. */
    var reservationGroupId: ReservationGroupId? = null
        private set
    lateinit var placedOn: Instant
        private set

    /** The end of the time to pay and of the stock hold (`PaymentInit.expiresAt`). Never moved, not even by a retry. */
    lateinit var holdUntil: Instant
        private set
    var paidOn: Instant? = null
        private set

    /** The attempt that paid it; null while unpaid, and for an order with nothing to pay. */
    var paidByPaymentId: PaymentPublicId? = null
        private set
    var customerNote: CustomerNote? = null
        private set
    var gift: GiftNote? = null
        private set

    private val lineList = mutableListOf<OrderLine>()
    private val failedAttempts = mutableSetOf<PaymentPublicId>()

    /** Its lines, by line id. */
    val lines: List<OrderLine> get() = lineList.toList()

    /** Placed and not paid yet: it may still be paid. */
    val isAwaitingPayment: Boolean get() = status.isAwaitingPayment

    /** Loading: rebuild from the stored events. */
    constructor(events: List<OrderDomainEvent>) : this() {
        applyEventStream(events)
    }

    /** A new payment attempt may start now ([PaymentPolicy.canStartPayment]). */
    fun canStartPayment(now: Instant): Boolean = PaymentPolicy.canStartPayment(status, holdUntil, now)

    companion object {
        /**
         * A new order, unpaid, its stock to be held until [paymentPending] from [now]. With nothing to pay it is paid at
         * once: [OrderPlaced] then [OrderPaid] without a payment.
         *
         * [lines] are numbered 1 to n and hold each color once, and [totals] are theirs (OrderPricing worked them out
         * from the same lines): anything else is a bug and stops loudly. [limits] has the limit per order of each
         * product in the lines.
         */
        fun place(
            id: OrderId,
            code: OrderCode,
            userId: UserId,
            lines: List<OrderLine>,
            limits: Map<ProductId, MaxPerOrder>,
            address: AddressSnapshot,
            messenger: PreShipmentMessenger,
            shippingMethod: ShippingMethodCode,
            totals: OrderTotals,
            reserveRequested: Boolean,
            customerNote: CustomerNote?,
            gift: GiftNote?,
            paymentPending: Duration,
            by: UserId,
            now: Instant,
            reservationGroupId: ReservationGroupId? = null,
        ): Result<Order, PlaceFailure> {
            if (lines.isEmpty()) return Result.failure(PlaceFailure.NoLines)
            require(lines.map { it.lineId } == (1..lines.size).toList()) { "Order lines must be numbered 1 to n: ${lines.map { it.lineId }}" }
            require(lines.distinctBy { it.variantId }.size == lines.size) { "An order holds each color once" }
            require(totals.linesCount == lines.size && totals.itemsCount == lines.sumOf { it.quantity.value }) { "The totals count other lines" }
            require(totals.subtotal == lines.map { it.totals.total }.sum()) { "The totals are not of these lines" }
            require(paymentPending > Duration.ZERO) { "The time to pay must be positive: $paymentPending" }
            require(reservationGroupId == null || !reserveRequested) { "An order joining a reservation doesn't start its own" }

            val overLimit = productsOverLimit(lines.map { it.productId to it.quantity.value }, limits)
            if (overLimit.isNotEmpty()) return Result.failure(PlaceFailure.LimitExceeded(overLimit.toList()))

            val order = Order()
            order.start { sequence ->
                OrderPlaced(
                    id, sequence, now, by, code, userId, lines, address, messenger, shippingMethod, totals, reserveRequested,
                    holdUntil = now.plus(paymentPending), customerNote, gift, reservationGroupId,
                )
            }
            return Result.success(order)
        }

        /**
         * The products whose colors together are more than the product's limit per order: the rule [place] keeps, for
         * whoever needs to know it before (OrderDomainService names the cart's lines). [quantities] are (product, how
         * many) per line; [limits] has every product in them.
         */
        fun productsOverLimit(quantities: List<Pair<ProductId, Int>>, limits: Map<ProductId, MaxPerOrder>): Set<ProductId> =
            quantities.groupBy({ it.first }, { it.second })
                .filter { (productId, ofProduct) ->
                    val limit = limits[productId] ?: error("No limit per order for product ${productId.value}")
                    ofProduct.sum() > limit.value
                }
                .keys
    }

    /** The first event, numbered by this new order, and the payment of an order with nothing to pay. */
    private fun start(placed: (EventSequence) -> OrderPlaced) {
        val event = placed(nextSequence())
        raiseEvent(event)
        if (event.totals.payable == Money.ZERO) {
            raiseEvent(OrderPaid(id, nextSequence(), event.occurredOn, event.occurredByUserId, paymentId = null, amount = Money.ZERO, heldForReservation = false))
        }
    }

    // ============ the money ============

    /**
     * The money came in: an attempt was confirmed by its gateway. Only while unpaid, and only exactly its payable (a
     * different amount is a bug: it is never paid that way). A callback after [holdUntil] is still taken, as long as
     * the order hasn't expired.
     *
     * @param heldForReservation it is held with «رزرو ۴ روزه» (RESERVED) instead of being prepared now (PROCESSING);
     *   decided by the caller with what is true now (ReservationDomainService), and kept in the event as decided.
     * @param reservationGroupId the group it is held in, or ships with; a group it joined when placed stays its own.
     */
    fun markPaid(
        paymentId: PaymentPublicId,
        amount: Money,
        heldForReservation: Boolean,
        by: UserId,
        now: Instant,
        reservationGroupId: ReservationGroupId? = null,
    ): Result<Order, MarkPaidFailure> {
        if (status.isPaid) return Result.failure(MarkPaidFailure.AlreadyPaid)
        if (!isAwaitingPayment) return Result.failure(MarkPaidFailure.NotPayable(status))
        if (amount != totals.payable) return Result.failure(MarkPaidFailure.AmountMismatch(expected = totals.payable, paid = amount))
        require(!heldForReservation || reservationGroupId != null) { "Order ${code.value} held for a reservation without its group" }
        require(this.reservationGroupId == null || reservationGroupId == this.reservationGroupId) { "Order ${code.value} joined another group when placed" }

        raiseEvent(OrderPaid(id, nextSequence(), now, by, paymentId, amount, heldForReservation, reservationGroupId))
        return Result.success(this)
    }

    /**
     * Its reservation's time is over: a held order goes to be prepared, with the rest of its group. Only a RESERVED
     * order moves; any other (let go already, or never held) changes nothing, so the job may run twice.
     */
    fun releaseFromReservation(by: UserId, now: Instant): Result<Order, Nothing> {
        if (status != RESERVED) return Result.success(this)
        raiseEvent(OrderReleasedFromReservation(id, nextSequence(), now, by))
        return Result.success(this)
    }

    /**
     * A payment attempt failed or was cancelled. Recorded once per attempt, and only while the order is unpaid: on a
     * paid or expired order, or for an attempt recorded already (a callback that came twice), it changes nothing.
     * [reason] is one that fails the order: never an order that couldn't be paid any more, nor one that expired.
     */
    fun recordPaymentFailure(paymentId: PaymentPublicId, reason: PaymentFailureReason, by: UserId, now: Instant): Result<Order, Nothing> {
        require(reason != PaymentFailureReason.ORDER_NOT_PAYABLE && reason != PaymentFailureReason.EXPIRED) { "$reason doesn't fail an order" }
        if (!isAwaitingPayment || paymentId in failedAttempts) return Result.success(this)

        raiseEvent(OrderPaymentFailed(id, nextSequence(), now, by, paymentId, reason))
        return Result.success(this)
    }

    /**
     * Not paid in time: the order expires, and its stock is to be given back. Only while unpaid and from [holdUntil] on;
     * an expired order expiring again changes nothing. Whether an attempt is still on the gateway's page is the
     * caller's to check first (PaymentPolicy.GATEWAY_WINDOW): the payments are not this aggregate's.
     */
    fun expire(by: UserId, now: Instant): Result<Order, ExpireFailure> {
        if (status == EXPIRED) return Result.success(this)
        if (!isAwaitingPayment) return Result.failure(ExpireFailure.NotAwaitingPayment(status))
        if (now.isBefore(holdUntil)) return Result.failure(ExpireFailure.TooEarly(holdUntil))

        raiseEvent(OrderExpired(id, nextSequence(), now, by))
        return Result.success(this)
    }

    override fun apply(event: OrderDomainEvent) {
        id = event.orderId
        when (event) {
            is OrderPlaced -> {
                code = event.code
                userId = event.userId
                lineList += event.lines
                address = event.address
                messenger = event.messenger
                shippingMethod = event.shippingMethod
                totals = event.totals
                reserveRequested = event.reserveRequested
                placedOn = event.occurredOn
                holdUntil = event.holdUntil
                customerNote = event.customerNote
                gift = event.gift
                reservationGroupId = event.reservationGroupId
                status = PENDING_PAYMENT
            }
            is OrderPaymentFailed -> {
                failedAttempts += event.paymentId
                status = PAYMENT_FAILED
            }
            is OrderPaid -> {
                paidOn = event.occurredOn
                paidByPaymentId = event.paymentId
                reservationGroupId = event.reservationGroupId ?: reservationGroupId
                status = if (event.heldForReservation) RESERVED else PROCESSING
            }
            is OrderExpired -> status = EXPIRED
            is OrderReleasedFromReservation -> status = PROCESSING
        }
    }

    override fun equals(other: Any?): Boolean = other is Order && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Order(id=${id.value}, code=${code.value}, status=$status)"

    // ============ failures, one sealed family per operation ============

    sealed class PlaceFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Nothing to order. */
        data object NoLines : PlaceFailure("CART_EMPTY", FailureKind.BUSINESS_RULE)

        /** The colors of these products together are more than the product's limit per order. */
        data class LimitExceeded(val productIds: List<ProductId>) :
            PlaceFailure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, mapOf("productIds" to productIds.map { it.value }))
    }

    sealed class MarkPaidFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object AlreadyPaid : MarkPaidFailure("ORDER_ALREADY_PAID", FailureKind.BUSINESS_RULE)

        /** Expired (or, in a later phase, cancelled): it can't be paid any more. */
        data class NotPayable(val status: OrderStatus) :
            MarkPaidFailure("ORDER_NOT_PAYABLE", FailureKind.BUSINESS_RULE, mapOf("status" to status.name))

        /** The gateway confirmed another amount than the order's payable: a bug, never a payment. */
        data class AmountMismatch(val expected: Money, val paid: Money) :
            MarkPaidFailure("PAYMENT_AMOUNT_MISMATCH", FailureKind.CONFLICT, mapOf("expected" to expected.toman, "paid" to paid.toman))
    }

    sealed class ExpireFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Paid (or, in a later phase, cancelled): nothing to expire. */
        data class NotAwaitingPayment(val status: OrderStatus) :
            ExpireFailure("ORDER_NOT_AWAITING_PAYMENT", FailureKind.CONFLICT, mapOf("status" to status.name))

        /** Its time to pay hasn't ended yet. */
        data class TooEarly(val holdUntil: Instant) :
            ExpireFailure("ORDER_NOT_DUE", FailureKind.CONFLICT, mapOf("holdUntil" to holdUntil.toString()))
    }
}
