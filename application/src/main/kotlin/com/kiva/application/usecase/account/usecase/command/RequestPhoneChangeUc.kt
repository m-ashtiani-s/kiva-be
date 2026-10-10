package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.auth.OtpIssuer
import com.kiva.application.usecase.auth.OtpIssuer.Recipient
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.Refusal
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * The first half of changing the phone (`POST /me/phone-change/request`): a code to the account's current phone and a
 * code to the new one. Both phones must be proved: with the new one alone, someone holding a stolen access token could
 * move the account to their own number for good. Whoever lost their current phone asks support instead.
 *
 * The code to the current phone names the new phone it allows (`authorizesPhone`), so it can't be used to move the
 * account anywhere else. Whether the new phone has an account is not looked at here, on purpose: the answer is the same
 * either way, so this can't be used to find out who shops at Kiva. Only after both codes are verified is it told.
 *
 * Both phones' limits apply (OtpIssuer), and the SMS go out after the commit. Unlike a customer's other requests, the
 * token's session must still be live: a token of a session that was logged out can't start this.
 */
class RequestPhoneChangeUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val otpIssuer: OtpIssuer,
    private val smsSender: SmsSender,
    private val clock: Clock,
) : CommandUseCase<RequestPhoneChangeUc.Cmd, RequestPhoneChangeUc.CodesSent, RequestPhoneChangeUc.Failure> {

    private val logger = getLogger()

    /** @property userId, sessionId from the access token. */
    data class Cmd(val userId: UserId, val sessionId: SessionId, val newPhone: String, val ip: String?) {
        // The typed phone is personal data.
        override fun toString(): String = "Cmd(user=${userId.value}, session=${sessionId.value}, newPhone=<hidden>, ip=$ip)"
    }

    /** Both phones in their stored form, for «a code went to …». */
    data class CodesSent(val currentPhone: IranianMobileNumber, val newPhone: IranianMobileNumber)

    private sealed interface Outcome {
        data class Issued(val user: User, val newPhone: IranianMobileNumber, val currentCode: OtpCode, val newCode: OtpCode) : Outcome

        data class Refused(val refusal: Refusal) : Outcome
    }

    override suspend fun execute(command: Cmd): Result<CodesSent, Failure> {
        logger.info("RequestPhoneChangeUc.execute $command")
        val newPhone = IranianMobileNumber.makeNew(command.newPhone).forField("newPhone")
            .unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }

        val now = clock.instant()
        val outcome = transactionManager.inTransaction<Outcome, Failure> {
            val session = authSessionRepository.findById(command.sessionId)
            if (session == null || session.userId != command.userId || !session.isActiveAt(now)) {
                return@inTransaction Result.failure(Failure.SessionGone)
            }
            val user = userRepository.findById(command.userId) ?: return@inTransaction Result.failure(Failure.SessionGone)
            if (newPhone == user.phone) return@inTransaction Result.failure(Failure.InvalidInput(listOf(User.ChangePhoneFailure.SameAsCurrent)))

            val toCurrent = Recipient(user.phone, OtpPurpose.PHONE_CHANGE_CURRENT)
            val toNew = Recipient(newPhone, OtpPurpose.PHONE_CHANGE_NEW)
            otpIssuer.lockAndCheck(listOf(toCurrent, toNew), now)?.let { return@inTransaction Result.success(Outcome.Refused(it)) }

            val currentCode = otpIssuer.issue(toCurrent, userId = user.id, authorizesPhone = newPhone, requestIp = command.ip, now = now)
            val newCode = otpIssuer.issue(toNew, userId = user.id, authorizesPhone = null, requestIp = command.ip, now = now)
            Result.success(Outcome.Issued(user, newPhone, currentCode, newCode))
        }.unwrapOr { return Result.failure(it) }

        return when (outcome) {
            is Outcome.Refused -> {
                logger.info("RequestPhoneChangeUc: no codes for user ${command.userId.value}, ${outcome.refusal}")
                Result.failure(Failure.LimitReached(outcome.refusal))
            }
            is Outcome.Issued -> {
                smsSender.sendCode(outcome.user.phone, outcome.currentCode, OtpPurpose.PHONE_CHANGE_CURRENT)
                smsSender.sendCode(outcome.newPhone, outcome.newCode, OtpPurpose.PHONE_CHANGE_NEW)
                Result.success(CodesSent(outcome.user.phone, outcome.newPhone))
            }
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** The new phone isn't one, or is the current one (PHONE_SAME_AS_CURRENT), under `newPhone`. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** The token's session was logged out or revoked, or its account is gone: log in again. */
        data object SessionGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        /** A limit of one of the two phones, or the shop's: 429, with how long to wait. */
        data class LimitReached(val refusal: Refusal) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }
}
