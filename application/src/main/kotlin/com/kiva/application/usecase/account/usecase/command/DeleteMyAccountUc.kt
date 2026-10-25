package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SecurityEvent
import com.kiva.application.port.SecurityEventLog
import com.kiva.application.port.SecurityEventType
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.account.AccountEraser
import com.kiva.application.usecase.account.ActiveOrdersCheck
import com.kiva.common.event.ConcurrentChangeException
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
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.repository.OrderRepository
import com.kiva.domain.aggregate.otp.entity.OtpChallenge
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.otp.repository.OtpChallengeRepository
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupRepository
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.model.valueobject.DeletionReason
import com.kiva.domain.aggregate.user.repository.AccountDeletionReasonRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import java.time.Clock
import java.time.Instant

/**
 * Deletes the logged-in user's account (`DELETE /me`, roadmap phase 14, section 19.6), in one transaction:
 * 1. the locks, in the order of section 4.11: the user's cart (2), wishlist (13), address book (3), reservation group
 *    (4), then the user's row (`FOR UPDATE`). An order being placed holds the cart, so this waits for it, and then sees
 *    it in the next step (decision 9); an order placed after this commits finds the account gone (401).
 * 2. again, under the locks: a staff account (422 STAFF_ACCOUNT_CANNOT_BE_DELETED), an active order (422
 *    ACCOUNT_HAS_ACTIVE_ORDERS with their codes). **Before** the code, so a code refused for these isn't used up.
 * 3. the DELETE_ACCOUNT code, as a login code is tried (the rollback trap: a wrong code is committed as a try, the
 *    failure made after the commit). A login code is no code here: the purpose is part of the hash.
 * 4. every order of the account, in any status, made anonymous by its own event (OrderPersonalDataErased, lock 8)
 * 5. what else goes with the account (AccountEraser, decision 4)
 * 6. the account erased (User.erase); every session revoked (ACCOUNT_DELETED). The access token of this very request
 *    stops working at once: the token check asks whether the account is deleted (decision 10).
 * 7. the reason, if one was given, without any id (decision 11)
 * 8. ACCOUNT_DELETED in the security log
 *
 * Another change of one of its orders at the same moment refuses the save of that order's stream: the whole
 * transaction runs again, up to [MAX_ATTEMPTS] times (section 4.5, rule 9).
 */
class DeleteMyAccountUc(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val wishlistRepository: WishlistRepository,
    private val addressBookRepository: AddressBookRepository,
    private val reservationGroupRepository: ReservationGroupRepository,
    private val userRepository: UserRepository,
    private val orderQmRepository: OrderQmRepository,
    private val activeOrdersCheck: ActiveOrdersCheck,
    private val otpChallengeRepository: OtpChallengeRepository,
    private val otpCodes: OtpCodes,
    private val orderRepository: OrderRepository,
    private val accountEraser: AccountEraser,
    private val authSessionRepository: AuthSessionRepository,
    private val accountDeletionReasonRepository: AccountDeletionReasonRepository,
    private val securityEventLog: SecurityEventLog,
    private val clock: Clock,
) : CommandUseCase<DeleteMyAccountUc.Cmd, Unit, DeleteMyAccountUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's body as it arrived, plus who and where.
     *
     * @property userId from the access token.
     */
    data class Cmd(val userId: UserId, val otpCode: String?, val reason: String?, val ip: String?) {
        // The code is a secret, and the reason may say anything.
        override fun toString(): String = "Cmd(user=${userId.value}, otpCode=<hidden>, reason=${if (reason == null) "none" else "<hidden>"}, ip=$ip)"
    }

    private class Input(val code: OtpCode, val reason: DeletionReason?)

    private sealed interface Outcome {
        data class Deleted(val ordersErased: Int, val sessionsEnded: Int) : Outcome

        /** The code was not right; the answer, made after the try was committed. */
        data class CodeRefused(val failure: Failure) : Outcome
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("DeleteMyAccountUc.execute user=${command.userId.value}")
        val input = Result.zip(
            OtpCode.makeNew(command.otpCode.orEmpty()).forField("otpCode"),
            DeletionReason.makeNew(command.reason),
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { code, reason -> Input(code, reason) }
            .unwrapOr { return Result.failure(it) }

        return when (val outcome = deleteWithRetries(command, input).unwrapOr { return Result.failure(it) }) {
            is Outcome.Deleted -> {
                logger.info("DeleteMyAccountUc: user ${command.userId.value} deleted; ${outcome.ordersErased} order(s) made anonymous, ${outcome.sessionsEnded} session(s) ended")
                Result.success(Unit)
            }
            is Outcome.CodeRefused -> {
                logger.info("DeleteMyAccountUc: user ${command.userId.value} not deleted, ${outcome.failure.code}")
                Result.failure(outcome.failure)
            }
        }
    }

    private suspend fun deleteWithRetries(command: Cmd, input: Input): Result<Outcome, Failure> {
        repeat(MAX_ATTEMPTS - 1) { attempt ->
            try {
                return deleteOnce(command, input)
            } catch (e: ConcurrentChangeException) {
                logger.info("DeleteMyAccountUc: an order changed meanwhile (${e.message}), attempt ${attempt + 1} of $MAX_ATTEMPTS refused; trying again")
            }
        }
        // The last attempt: if even this one is refused, the exception goes up to the caller.
        return deleteOnce(command, input)
    }

    private suspend fun deleteOnce(command: Cmd, input: Input): Result<Outcome, Failure> = transactionManager.inTransaction {
        val now = clock.instant()
        val userId = command.userId
        cartRepository.lockForUser(userId)
        wishlistRepository.lockFor(userId)
        addressBookRepository.lockAndFind(userId)
        reservationGroupRepository.lockFor(userId)
        val user = userRepository.lockAndFind(userId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.AccountGone)

        if (user.isStaff) return@inTransaction Result.failure(Failure.StaffAccount)
        val orders = orderQmRepository.findAllOfCustomer(user.id)
        val active = activeOrdersCheck.activeOf(orders)
        if (active.isNotEmpty()) return@inTransaction Result.failure(Failure.ActiveOrders(active.map { it.code }))

        tryCode(user, input.code, command.ip, now)?.let { return@inTransaction Result.success(Outcome.CodeRefused(it)) }

        orders.forEach { row ->
            val order = orderRepository.get(row.orderId) ?: error("Order ${row.code.value} is in the read model but has no events")
            order.erasePersonalData(ErasureReason.ACCOUNT_DELETED, user.id, now)
            orderRepository.save(order)
        }
        val erased = accountEraser.eraseAllOf(user)
        userRepository.save(user.erase(now))
        val sessionsEnded = authSessionRepository.revokeAllOf(user.id, RevokeReason.ACCOUNT_DELETED, now)
        input.reason?.let { accountDeletionReasonRepository.add(it, now) }
        securityEventLog.record(
            SecurityEvent(
                SecurityEventType.ACCOUNT_DELETED, now, userId = user.id, ip = command.ip,
                detail = mapOf("ordersErased" to orders.size, "sessionsEnded" to sessionsEnded, "reviewsAnonymized" to erased.reviewsAnonymized),
            ),
        )
        Result.success(Outcome.Deleted(orders.size, sessionsEnded))
    }

    /**
     * Tries the DELETE_ACCOUNT code of the account's phone, as a login code is tried: the open code is locked first, the
     * day's wrong codes checked (ten stop every try), then this one. Null when it was right (it is used up with the
     * deletion); otherwise the answer, with the wrong try saved and its security events recorded.
     */
    private fun tryCode(user: User, code: OtpCode, ip: String?, now: Instant): Failure? {
        val phone = user.activePhone
        val challenge = otpChallengeRepository.findOpenForUpdate(phone, OtpPurpose.DELETE_ACCOUNT)?.takeIf { it.userId == user.id }
        lockedNow(user, now)?.let { return Failure.Locked(it) }
        if (challenge == null) return Failure.NoActiveCode

        val attempt = challenge.attempt(otpCodes.hash(code, challenge.salt, OtpPurpose.DELETE_ACCOUNT, phone), now)
        otpChallengeRepository.save(attempt.challenge)
        if (attempt.challenge.failedAttempts > challenge.failedAttempts) {
            val left = OtpPolicy.MAX_ATTEMPTS - attempt.challenge.failedAttempts
            securityEventLog.record(
                SecurityEvent(
                    SecurityEventType.OTP_VERIFY_FAILED, now, userId = user.id, phone = phone, ip = ip,
                    detail = mapOf("purpose" to OtpPurpose.DELETE_ACCOUNT.name, "attemptsLeft" to left),
                ),
            )
            lockedNow(user, now)?.let { refusal ->
                securityEventLog.record(
                    SecurityEvent(SecurityEventType.OTP_LOCKED, now, userId = user.id, phone = phone, ip = ip, detail = mapOf("until" to now.plus(refusal.retryAfter).toString())),
                )
                return Failure.Locked(refusal)
            }
        }
        return when (val result = attempt.outcome) {
            OtpChallenge.Outcome.Accepted -> null
            is OtpChallenge.Outcome.Wrong -> Failure.WrongCode(result.attemptsLeft)
            OtpChallenge.Outcome.Exhausted -> Failure.TooManyAttempts
            OtpChallenge.Outcome.Expired -> Failure.NoActiveCode
        }
    }

    private fun lockedNow(user: User, now: Instant): OtpPolicy.Refusal.TooManyWrongCodes? =
        OtpPolicy.refusalToVerify(otpChallengeRepository.sentTo(user.activePhone, now.minus(OtpPolicy.DAY)), now)

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        /** The code's format or the reason, each under its field. Takes no try. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** The token's account is gone or was deleted already: log in again. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        /** A panel role: an admin takes the roles first (decision 6). */
        data object StaffAccount : Failure("STAFF_ACCOUNT_CANNOT_BE_DELETED", FailureKind.BUSINESS_RULE)

        /** Orders still open with the shop (decision 5); the code was not tried. */
        data class ActiveOrders(val orderCodes: List<OrderCode>) :
            Failure("ACCOUNT_HAS_ACTIVE_ORDERS", FailureKind.BUSINESS_RULE, meta = mapOf("orderCodes" to orderCodes.map { it.value }))

        /** Not the code; [attemptsLeft] more tries of this one. */
        data class WrongCode(val attemptsLeft: Int) : Failure("OTP_INVALID", FailureKind.BUSINESS_RULE, meta = mapOf("attemptsLeft" to attemptsLeft))

        /** This code took its five wrong tries; a new one is needed. */
        data object TooManyAttempts : Failure("OTP_TOO_MANY_ATTEMPTS", FailureKind.BUSINESS_RULE)

        /** No active deletion code: never asked for, expired, used, or replaced by a newer one. */
        data object NoActiveCode : Failure("OTP_EXPIRED", FailureKind.BUSINESS_RULE)

        /** Ten wrong codes for this phone today: no try at all until enough of them are a day old. */
        data class Locked(val refusal: OtpPolicy.Refusal.TooManyWrongCodes) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}
