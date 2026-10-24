package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.dto.IssuedSession
import com.kiva.application.port.AccessTokens
import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.port.SessionTokens
import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.repository.PhoneTakenException
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The second half of changing the phone (`POST /me/phone-change/verify`), in this order:
 * 1. both codes are tried: the current phone's (which must name this new phone) and the new phone's
 * 2. only then, is the new phone another account's? (409 PHONE_ALREADY_REGISTERED; told no sooner, so the request can't
 *    be used to find out who has an account)
 * 3. the account moves to the new phone, every session of it ends (PHONE_CHANGED), a new session starts for this device
 * 4. after the commit, a warning SMS goes to the old phone
 *
 * Both codes are tried each time. A wrong one takes a try, committed even though the answer is a failure (the
 * rollback trap, as at login). A right one is not used up while the other is wrong, so only the wrong one has to be
 * typed again. The 422 says which code is the problem in `meta.field` (`currentPhoneCode` or `newPhoneCode`). Ten wrong
 * codes a day for either phone stop everything, as at login.
 *
 * The old phone is free afterwards: logging in with it makes a new, empty account (isNewUser). That is intended: the
 * number has left this account, and whoever has it now is someone else.
 *
 * The token's session must still be live, as for the request.
 */
class ConfirmPhoneChangeUc(
    private val transactionManager: TransactionManager,
    private val otpChallengeRepository: OtpChallengeRepository,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val otpCodes: OtpCodes,
    private val sessionTokens: SessionTokens,
    private val accessTokens: AccessTokens,
    private val smsSender: SmsSender,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<ConfirmPhoneChangeUc.Cmd, IssuedSession, ConfirmPhoneChangeUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's verify request as it arrived, plus who and where.
     *
     * @property userId, sessionId from the access token.
     */
    data class Cmd(
        val userId: UserId,
        val sessionId: SessionId,
        val newPhone: String,
        val currentPhoneCode: String,
        val newPhoneCode: String,
        val deviceName: String?,
        val userAgent: String?,
        val ip: String?,
    ) {
        // A phone is personal data and the codes are secrets.
        override fun toString(): String = "Cmd(user=${userId.value}, session=${sessionId.value}, newPhone=<hidden>, codes=<hidden>, ip=$ip)"
    }

    private data class Input(val newPhone: IranianMobileNumber, val currentCode: OtpCode, val newCode: OtpCode)

    private sealed interface Outcome {
        class Changed(val session: IssuedSession, val oldPhone: IranianMobileNumber, val newPhone: IranianMobileNumber) : Outcome

        /** A code was not right; what to answer, after the tries were saved. */
        data class CodeRefused(val failure: Failure) : Outcome

        /** Both codes were right, but the new phone is another account's. */
        data object Taken : Outcome
    }

    override suspend fun execute(command: Cmd): Result<IssuedSession, Failure> {
        logger.info("ConfirmPhoneChangeUc.execute $command")
        val input = Result.zip(
            IranianMobileNumber.makeNew(command.newPhone).forField("newPhone"),
            OtpCode.makeNew(command.currentPhoneCode).forField(CURRENT_FIELD),
            OtpCode.makeNew(command.newPhoneCode).forField(NEW_FIELD),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { phone, current, new -> Input(phone, current, new) }
            .unwrapOr { return Result.failure(it) }

        val now = clock.instant()
        val device = SessionDevice.of(command.deviceName, command.userAgent, command.ip)
        val outcome = transactionManager.inTransaction<Outcome, Failure> {
            val session = authSessionRepository.findById(command.sessionId)
            if (session == null || session.userId != command.userId || !session.isActiveAt(now)) {
                return@inTransaction Result.failure(Failure.SessionGone)
            }
            val user = userRepository.findById(command.userId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.SessionGone)
            if (input.newPhone == user.activePhone) return@inTransaction Result.failure(Failure.InvalidInput(listOf(User.ChangePhoneFailure.SameAsCurrent)))

            tryCodes(user, input, command.ip, now)?.let { return@inTransaction Result.success(it) }

            // Both codes were right, and are used up whatever comes next.
            if (userRepository.findByPhone(input.newPhone) != null) return@inTransaction Result.success(Outcome.Taken)
            val moved = user.changePhone(input.newPhone).unwrapOr { error("refused: $it") }
            try {
                userRepository.save(moved)
            } catch (e: PhoneTakenException) {
                // Another account took it between the check and the save: the database refused, so nothing can be saved.
                logger.info("ConfirmPhoneChangeUc: ${e.message}")
                return@inTransaction Result.failure(Failure.PhoneTaken)
            }
            Result.success(Outcome.Changed(startOver(user, moved, device, command.ip, now), user.activePhone, input.newPhone))
        }.unwrapOr { return Result.failure(it) }

        return when (outcome) {
            is Outcome.Changed -> {
                warnOldPhone(outcome.oldPhone, outcome.newPhone)
                Result.success(outcome.session)
            }
            is Outcome.CodeRefused -> Result.failure(outcome.failure)
            Outcome.Taken -> Result.failure(Failure.PhoneTaken)
        }
    }

    /**
     * Tries both codes. Null when both were right (both are saved as used); otherwise what to answer, with only the
     * wrong tries saved.
     */
    private fun tryCodes(user: User, input: Input, ip: String?, now: Instant): Outcome? {
        // Locked in the order of their phones, so two requests about the same two codes can't wait for each other.
        val (current, new) = if (user.activePhone.value < input.newPhone.value) {
            val current = otpChallengeRepository.findOpenForUpdate(user.activePhone, OtpPurpose.PHONE_CHANGE_CURRENT)
            current to otpChallengeRepository.findOpenForUpdate(input.newPhone, OtpPurpose.PHONE_CHANGE_NEW)
        } else {
            val new = otpChallengeRepository.findOpenForUpdate(input.newPhone, OtpPurpose.PHONE_CHANGE_NEW)
            otpChallengeRepository.findOpenForUpdate(user.activePhone, OtpPurpose.PHONE_CHANGE_CURRENT) to new
        }
        lockedNow(user.activePhone, input.newPhone, now)?.let { return Outcome.CodeRefused(it) }

        // A code of another user, or one that allows another new phone, is no code for this change.
        val currentAttempt = current?.takeIf { it.userId == user.id && it.authorizesPhone == input.newPhone }
            ?.let { it.attempt(otpCodes.hash(input.currentCode, it.salt, OtpPurpose.PHONE_CHANGE_CURRENT, user.activePhone), now) }
        val newAttempt = new?.takeIf { it.userId == user.id }
            ?.let { it.attempt(otpCodes.hash(input.newCode, it.salt, OtpPurpose.PHONE_CHANGE_NEW, input.newPhone), now) }

        if (currentAttempt?.outcome == OtpChallenge.Outcome.Accepted && newAttempt?.outcome == OtpChallenge.Outcome.Accepted) {
            otpChallengeRepository.save(currentAttempt.challenge)
            otpChallengeRepository.save(newAttempt.challenge)
            return null
        }

        // Only the wrong tries are saved: a right code stays usable while the other one is retyped.
        listOfNotNull(current to currentAttempt, new to newAttempt).forEach { (before, attempt) ->
            if (before != null && attempt != null && attempt.challenge.failedAttempts > before.failedAttempts) {
                otpChallengeRepository.save(attempt.challenge)
                securityEventLog.record(
                    SecurityEvent(
                        SecurityEventType.OTP_VERIFY_FAILED, now, userId = user.id, phone = before.phone, ip = ip,
                        detail = mapOf("purpose" to before.purpose.name, "attemptsLeft" to OtpPolicy.MAX_ATTEMPTS - attempt.challenge.failedAttempts),
                    ),
                )
            }
        }
        lockedNow(user.activePhone, input.newPhone, now)?.let { locked ->
            securityEventLog.record(SecurityEvent(SecurityEventType.OTP_LOCKED, now, userId = user.id, ip = ip, detail = mapOf("until" to now.plus(locked.refusal.retryAfter).toString())))
            return Outcome.CodeRefused(locked)
        }
        return Outcome.CodeRefused(refusalOf(currentAttempt, CURRENT_FIELD) ?: refusalOf(newAttempt, NEW_FIELD) ?: error("both codes were right"))
    }

    /** What to answer about one code; null when it was right. */
    private fun refusalOf(attempt: OtpChallenge.Attempt?, field: String): Failure? = when (val outcome = attempt?.outcome) {
        null, OtpChallenge.Outcome.Expired -> Failure.NoActiveCode(field)
        OtpChallenge.Outcome.Exhausted -> Failure.TooManyAttempts(field)
        is OtpChallenge.Outcome.Wrong -> Failure.WrongCode(field, outcome.attemptsLeft)
        OtpChallenge.Outcome.Accepted -> null
    }

    private fun lockedNow(currentPhone: IranianMobileNumber, newPhone: IranianMobileNumber, now: Instant): Failure.Locked? =
        listOf(currentPhone, newPhone).firstNotNullOfOrNull { phone ->
            OtpPolicy.refusalToVerify(otpChallengeRepository.sentTo(phone, now.minus(OtpPolicy.DAY)), now)
        }?.let { Failure.Locked(it) }

    /** Every session of the account ends, and this device gets a new one, with the new phone in its access token. */
    private fun startOver(before: User, after: User, device: SessionDevice, ip: String?, now: Instant): IssuedSession {
        val ended = authSessionRepository.revokeAllOf(after.id, RevokeReason.PHONE_CHANGED, now)
        val session = AuthSession.start(authSessionRepository.nextId(), after.id, after.roles, now, device)
        val refreshToken = sessionTokens.newRefreshToken()
        authSessionRepository.add(session, refreshToken.hash)
        val accessToken = accessTokens.issue(after, session, now)
        securityEventLog.record(
            SecurityEvent(
                SecurityEventType.PHONE_CHANGED, now, userId = after.id, sessionId = session.id, ip = ip,
                detail = mapOf("from" to before.activePhone.masked(), "to" to after.activePhone.masked(), "sessionsEnded" to ended),
            ),
        )
        logger.info("ConfirmPhoneChangeUc: user ${after.id.value} moved from ${before.activePhone.masked()} to ${after.activePhone.masked()}; $ended session(s) ended")
        return IssuedSession(
            userId = after.id,
            isNewUser = false,
            accessToken = accessToken.value,
            accessTokenExpiresIn = accessToken.expiresIn,
            refreshToken = refreshToken.token,
            refreshTokenMaxAge = Duration.between(now, session.idleExpiresOn),
        )
    }

    /** The change is made and stays made if this fails; a missed warning is logged loudly instead. */
    private suspend fun warnOldPhone(oldPhone: IranianMobileNumber, newPhone: IranianMobileNumber) {
        try {
            smsSender.sendPhoneChangedNotice(oldPhone, newPhone.value.takeLast(4))
        } catch (e: Exception) {
            logger.error("ConfirmPhoneChangeUc: the warning to ${oldPhone.masked()} could not be sent", e)
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** The new phone or a code's format, each under its field; or the new phone is the current one. Takes no try. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** The code in [codeField] is wrong; [attemptsLeft] more tries of it. */
        data class WrongCode(val codeField: String, val attemptsLeft: Int) :
            Failure("OTP_INVALID", FailureKind.BUSINESS_RULE, meta = mapOf("field" to codeField, "attemptsLeft" to attemptsLeft))

        /** The code in [codeField] took its five wrong tries: new codes are needed. */
        data class TooManyAttempts(val codeField: String) : Failure("OTP_TOO_MANY_ATTEMPTS", FailureKind.BUSINESS_RULE, meta = mapOf("field" to codeField))

        /** No active code for [codeField]: never sent for this new phone, expired, used, or replaced. */
        data class NoActiveCode(val codeField: String) : Failure("OTP_EXPIRED", FailureKind.BUSINESS_RULE, meta = mapOf("field" to codeField))

        /** Ten wrong codes today for one of the phones. */
        data class Locked(val refusal: OtpPolicy.Refusal.TooManyWrongCodes) : Failure(refusal.code, refusal.kind, meta = refusal.meta)

        /** The new phone is another account's. */
        data object PhoneTaken : Failure("PHONE_ALREADY_REGISTERED", FailureKind.CONFLICT)

        /** The token's session was logged out or revoked, or its account is gone: log in again. */
        data object SessionGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)
    }

    private companion object {
        const val CURRENT_FIELD = "currentPhoneCode"
        const val NEW_FIELD = "newPhoneCode"
    }
}
