package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.account.ActiveOrdersCheck
import com.kiva.application.usecase.auth.OtpIssuer
import com.kiva.application.usecase.auth.OtpIssuer.Recipient
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy.Refusal
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * The first half of deleting the account (`POST /me/deletion/request`, roadmap phase 14, section 19.6): a code for
 * DELETE_ACCOUNT to the account's own phone.
 *
 * What would stop the deletion is checked **first**, so no SMS is paid for a code that can't be used (decision 7): a
 * staff account (any panel role, 422 STAFF_ACCOUNT_CANNOT_BE_DELETED: an admin takes the roles first), then an active
 * order (422 ACCOUNT_HAS_ACTIVE_ORDERS with their codes, ActiveOrdersCheck). DELETE /me checks both again under its
 * locks: a check here is only an early answer.
 *
 * Then the same limits as a login code (OtpIssuer: the phone's own, the shop's hourly cap), committed even when refused
 * so the security log keeps the cap. The SMS goes after the commit. A login code is never taken for the deletion, nor
 * this code for a login: the purpose is part of the code's hash.
 */
class RequestAccountDeletionUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val activeOrdersCheck: ActiveOrdersCheck,
    private val otpIssuer: OtpIssuer,
    private val smsSender: SmsSender,
    private val clock: Clock,
) : CommandUseCase<RequestAccountDeletionUc.Cmd, IranianMobileNumber, RequestAccountDeletionUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [ip] as the HTTP layer saw it. */
    data class Cmd(val userId: UserId, val ip: String?)

    private sealed interface Outcome {
        class Issued(val phone: IranianMobileNumber, val code: OtpCode) : Outcome

        data class Refused(val refusal: Refusal) : Outcome
    }

    override suspend fun execute(command: Cmd): Result<IranianMobileNumber, Failure> {
        logger.info("RequestAccountDeletionUc.execute user=${command.userId.value}")
        val now = clock.instant()
        val outcome = transactionManager.inTransaction<Outcome, Failure> {
            val user = userRepository.findById(command.userId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.AccountGone)
            if (user.isStaff) return@inTransaction Result.failure(Failure.StaffAccount)
            val active = activeOrdersCheck.activeOrderCodesOf(user.id)
            if (active.isNotEmpty()) return@inTransaction Result.failure(Failure.ActiveOrders(active))

            val recipient = Recipient(user.activePhone, OtpPurpose.DELETE_ACCOUNT)
            otpIssuer.lockAndCheck(listOf(recipient), now)?.let { return@inTransaction Result.success(Outcome.Refused(it)) }
            Result.success(Outcome.Issued(recipient.phone, otpIssuer.issue(recipient, userId = user.id, authorizesPhone = null, requestIp = command.ip, now = now)))
        }.unwrapOr {
            logger.info("RequestAccountDeletionUc: no code for user ${command.userId.value}, ${it.code}")
            return Result.failure(it)
        }

        return when (outcome) {
            is Outcome.Refused -> {
                logger.info("RequestAccountDeletionUc: no code for user ${command.userId.value}, ${outcome.refusal}")
                Result.failure(Failure.LimitReached(outcome.refusal))
            }
            is Outcome.Issued -> {
                smsSender.sendCode(outcome.phone, outcome.code, OtpPurpose.DELETE_ACCOUNT)
                Result.success(outcome.phone)
            }
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** The token's account is gone or was deleted: log in again. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        /** A panel role: an admin takes the roles first (decision 6). */
        data object StaffAccount : Failure("STAFF_ACCOUNT_CANNOT_BE_DELETED", FailureKind.BUSINESS_RULE)

        /** Orders still open with the shop (decision 5), named so the page can link them. */
        data class ActiveOrders(val orderCodes: List<OrderCode>) :
            Failure("ACCOUNT_HAS_ACTIVE_ORDERS", FailureKind.BUSINESS_RULE, meta = mapOf("orderCodes" to orderCodes.map { it.value }))

        /** A limit of the phone, or the shop's: 429, with how long to wait. */
        data class LimitReached(val refusal: Refusal) : Failure(refusal.code, refusal.kind, meta = refusal.meta)
    }
}
