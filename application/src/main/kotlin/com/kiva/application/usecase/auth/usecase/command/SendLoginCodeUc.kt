package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.auth.OtpIssuer
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.Refusal
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import java.time.Clock

/**
 * Sends a login code (`POST /auth/otp/send`). Logging in and signing up are the same thing here.
 *
 * It never looks at users, on purpose: the answer for a phone with an account and one without is the same, so the
 * endpoint can't be used to find out who shops at Kiva. Whether the phone has an account is told only after its code
 * is verified.
 *
 * In one transaction, under the phone's lock (ten sends at the same moment run one after another, so only one SMS
 * goes out): the limits are checked, the phone's open login code is replaced, and the new one is stored as a salted
 * HMAC ([OtpIssuer]). The SMS goes out after the commit, so the transaction never waits for the provider. If the
 * provider fails, the answer is a 500 and the stored code still counts against the limits: the safe side, so failures
 * can't be used to send without limit.
 *
 * A refused send is a successful outcome inside the transaction and a failure only after it: the refusal at the
 * shop's hourly cap records a security event, which a failed transaction would roll back.
 *
 * @return the phone in its stored form, for the response's «sent to ...».
 */
class SendLoginCodeUc(
    private val transactionManager: TransactionManager,
    private val otpIssuer: OtpIssuer,
    private val smsSender: SmsSender,
    private val clock: Clock,
) : CommandUseCase<SendLoginCodeUc.Cmd, IranianMobileNumber, SendLoginCodeUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's SendOtpRequest as it arrived, plus where it came from.
     *
     * @property purpose only LOGIN or nothing: the other codes have endpoints of their own.
     * @property ip the client's address, as the HTTP layer sees it; stored with the code.
     */
    data class Cmd(val phone: String, val purpose: String?, val ip: String?) {
        // The phone as typed is personal data and may be anything; it stays out of logs.
        override fun toString(): String = "Cmd(phone=<hidden>, purpose=$purpose, ip=$ip)"
    }

    private sealed interface Outcome {
        data class Issued(val code: OtpCode) : Outcome

        data class Refused(val refusal: Refusal) : Outcome
    }

    override suspend fun execute(command: Cmd): Result<IranianMobileNumber, Failure> {
        val phone = Result.zip(
            IranianMobileNumber.makeNew(command.phone),
            purposeOf(command.purpose),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { phone, _ -> phone }
            .unwrapOr {
                logger.info("SendLoginCodeUc.execute refused input: ${it.problems.map(ResultFailure::code)}")
                return Result.failure(it)
            }
        logger.info("SendLoginCodeUc.execute phone=${phone.masked()} ip=${command.ip}")

        val now = clock.instant()
        val recipient = OtpIssuer.Recipient(phone, OtpPurpose.LOGIN)
        val outcome = transactionManager.inTransaction<Outcome, Nothing> {
            val refusal = otpIssuer.lockAndCheck(listOf(recipient), now)
            if (refusal != null) return@inTransaction Result.success(Outcome.Refused(refusal))
            Result.success(Outcome.Issued(otpIssuer.issue(recipient, userId = null, authorizesPhone = null, requestIp = command.ip, now = now)))
        }.unwrapOr { it }

        return when (outcome) {
            is Outcome.Refused -> {
                logger.info("SendLoginCodeUc: no code for ${phone.masked()}, ${outcome.refusal}")
                Result.failure(Failure.LimitReached(outcome.refusal))
            }
            is Outcome.Issued -> {
                smsSender.sendCode(phone, outcome.code, OtpPurpose.LOGIN)
                Result.success(phone)
            }
        }
    }

    /** Only LOGIN: a phone change's or an account deletion's code can't be asked for here. */
    private fun purposeOf(raw: String?): Result<OtpPurpose, ResultFailure> =
        if (raw == null || raw == OtpPurpose.LOGIN.name) Result.success(OtpPurpose.LOGIN) else Result.failure(PurposeNotAllowed)

    /** The `purpose` of the request is not LOGIN. One of the problems in [Failure.InvalidInput]. */
    data object PurposeNotAllowed : ResultFailure("OTP_PURPOSE_INVALID", FailureKind.INVALID, field = "purpose")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** The phone or the purpose, each under its field. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** A limit says no code now: 429, with how long to wait. */
        data class LimitReached(val refusal: Refusal) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }
}
