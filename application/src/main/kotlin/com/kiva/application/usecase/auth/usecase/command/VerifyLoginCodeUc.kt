package com.kiva.application.usecase.auth.usecase.command

import com.kiva.application.dto.IssuedSession
import com.kiva.application.port.AccessTokens
import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.port.SessionTokens
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Logs in with a code (`POST /auth/otp/verify`): the right code of the phone's open login code makes a session, and the
 * phone's account if it had none (`isNewUser`). Only now is it told whether the phone has an account.
 *
 * The rollback trap, the most important rule here: the transaction rolls back on a Failure, and a wrong code must
 * count. So inside the transaction every answer, a wrong code included, is a successful [Outcome] that commits the
 * changed count; it becomes a Failure only after the commit. Returning a Failure from inside would roll the count
 * back with it, and the code could be guessed without limit. The same holds for the security events.
 *
 * In one transaction: the phone's open login code is read and locked (tries of one code run one after another), the
 * day's wrong codes are checked (10 stop every try, even of the right code), and the code is tried. A wrong code takes
 * one try; the right one is consumed, finds or makes the account, and starts a session with its first refresh token
 * (stored as a hash) and an access token.
 *
 * A code in the wrong format (not 5 digits, Persian digits are fine) is refused before anything, and takes no try.
 */
class VerifyLoginCodeUc(
    private val transactionManager: TransactionManager,
    private val otpChallengeRepository: OtpChallengeRepository,
    private val userRepository: UserRepository,
    private val authSessionRepository: AuthSessionRepository,
    private val otpCodes: OtpCodes,
    private val sessionTokens: SessionTokens,
    private val accessTokens: AccessTokens,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<VerifyLoginCodeUc.Cmd, IssuedSession, VerifyLoginCodeUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's VerifyOtpRequest as it arrived, plus where it came from.
     *
     * @property deviceName the client's name for the device, for display only; [userAgent] and [ip] as the HTTP layer saw them.
     */
    data class Cmd(val phone: String, val code: String, val deviceName: String?, val userAgent: String?, val ip: String?) {
        // The typed phone is personal data and the code is a secret: neither goes into a log.
        override fun toString(): String = "Cmd(phone=<hidden>, code=<hidden>, deviceName=$deviceName, ip=$ip)"
    }

    private sealed interface Outcome {
        class LoggedIn(val session: IssuedSession) : Outcome

        data class WrongCode(val attemptsLeft: Int) : Outcome

        data object TooManyAttempts : Outcome

        data object NoActiveCode : Outcome

        data class Locked(val refusal: OtpPolicy.Refusal.TooManyWrongCodes) : Outcome
    }

    override suspend fun execute(command: Cmd): Result<IssuedSession, Failure> {
        val (phone, code) = Result.zip(
            IranianMobileNumber.makeNew(command.phone),
            OtpCode.makeNew(command.code).forField("code"),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { phone, code -> phone to code }
            .unwrapOr {
                logger.info("VerifyLoginCodeUc.execute refused input: ${it.problems.map(ResultFailure::code)}")
                return Result.failure(it)
            }
        logger.info("VerifyLoginCodeUc.execute phone=${phone.masked()} ip=${command.ip}")

        val now = clock.instant()
        val device = SessionDevice.of(command.deviceName, command.userAgent, command.ip)
        val outcome = transactionManager.inTransaction<Outcome, Nothing> {
            Result.success(tryCode(phone, code, device, command.ip, now))
        }.unwrapOr { it }

        logger.info("VerifyLoginCodeUc: ${phone.masked()} -> ${outcome::class.simpleName}")
        return when (outcome) {
            is Outcome.LoggedIn -> Result.success(outcome.session)
            is Outcome.WrongCode -> Result.failure(Failure.WrongCode(outcome.attemptsLeft))
            Outcome.TooManyAttempts -> Result.failure(Failure.TooManyAttempts)
            Outcome.NoActiveCode -> Result.failure(Failure.NoActiveCode)
            is Outcome.Locked -> Result.failure(Failure.Locked(outcome.refusal))
        }
    }

    /** Everything that is committed, whatever the answer. */
    private fun tryCode(phone: IranianMobileNumber, code: OtpCode, device: SessionDevice, ip: String?, now: Instant): Outcome {
        // The lock first, so the day's count read next can't change under this try.
        val challenge = otpChallengeRepository.findOpenForUpdate(phone, OtpPurpose.LOGIN)
        lockedNow(phone, now)?.let { return Outcome.Locked(it) }
        if (challenge == null) return Outcome.NoActiveCode

        val attempt = challenge.attempt(otpCodes.hash(code, challenge.salt, OtpPurpose.LOGIN, phone), now)
        otpChallengeRepository.save(attempt.challenge)

        val wrongTryCounted = attempt.challenge.failedAttempts > challenge.failedAttempts
        if (wrongTryCounted) {
            val left = OtpPolicy.MAX_ATTEMPTS - attempt.challenge.failedAttempts
            securityEventLog.record(
                SecurityEvent(SecurityEventType.OTP_VERIFY_FAILED, now, phone = phone, ip = ip, detail = mapOf("purpose" to OtpPurpose.LOGIN.name, "attemptsLeft" to left)),
            )
            // This wrong code may be the day's last allowed one: the phone is locked from now on.
            lockedNow(phone, now)?.let { refusal ->
                securityEventLog.record(
                    SecurityEvent(SecurityEventType.OTP_LOCKED, now, phone = phone, ip = ip, detail = mapOf("until" to now.plus(refusal.retryAfter).toString())),
                )
                return Outcome.Locked(refusal)
            }
        }

        return when (val result = attempt.outcome) {
            OtpChallenge.Outcome.Accepted -> Outcome.LoggedIn(startSession(phone, device, ip, now))
            is OtpChallenge.Outcome.Wrong -> Outcome.WrongCode(result.attemptsLeft)
            OtpChallenge.Outcome.Exhausted -> Outcome.TooManyAttempts
            OtpChallenge.Outcome.Expired -> Outcome.NoActiveCode
        }
    }

    private fun lockedNow(phone: IranianMobileNumber, now: Instant): OtpPolicy.Refusal.TooManyWrongCodes? =
        OtpPolicy.refusalToVerify(otpChallengeRepository.sentTo(phone, now.minus(OtpPolicy.DAY)), now)

    private fun startSession(phone: IranianMobileNumber, device: SessionDevice, ip: String?, now: Instant): IssuedSession {
        val (user, isNew) = userRepository.findOrRegister(phone, now)
        if (isNew) securityEventLog.record(SecurityEvent(SecurityEventType.USER_REGISTERED, now, userId = user.id, phone = phone, ip = ip))

        val session = AuthSession.start(authSessionRepository.nextId(), user.id, user.roles, now, device)
        val refreshToken = sessionTokens.newRefreshToken()
        authSessionRepository.add(session, refreshToken.hash)
        val accessToken = accessTokens.issue(user, session, now)
        securityEventLog.record(
            SecurityEvent(SecurityEventType.LOGIN_SUCCEEDED, now, userId = user.id, sessionId = session.id, ip = ip, detail = mapOf("sessionKind" to session.kind.name)),
        )
        return IssuedSession(
            userId = user.id,
            isNewUser = isNew,
            accessToken = accessToken.value,
            accessTokenExpiresIn = accessToken.expiresIn,
            refreshToken = refreshToken.token,
            refreshTokenMaxAge = Duration.between(now, session.idleExpiresOn),
        )
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** The phone or the code's format, each under its field. Takes no try. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** Not the code; [attemptsLeft] more tries of this one. */
        data class WrongCode(val attemptsLeft: Int) :
            Failure("OTP_INVALID", FailureKind.BUSINESS_RULE, meta = mapOf("attemptsLeft" to attemptsLeft))

        /** This code took its five wrong tries; a new one is needed. */
        data object TooManyAttempts : Failure("OTP_TOO_MANY_ATTEMPTS", FailureKind.BUSINESS_RULE)

        /** No active code for this phone: never sent, expired, used, or replaced by a newer one. */
        data object NoActiveCode : Failure("OTP_EXPIRED", FailureKind.BUSINESS_RULE)

        /** Ten wrong codes for this phone today: no try at all until enough of them are a day old. */
        data class Locked(val refusal: OtpPolicy.Refusal.TooManyWrongCodes) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }
}
