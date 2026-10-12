package com.kiva.application.usecase.checkout.usecase.query

import com.kiva.application.dto.PaymentResultDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * How an attempt to pay went (`GET /payments/{paymentId}`, roadmap section 10.7): the result page the gateway's callback
 * sends the shopper to. PENDING while it is being confirmed, and the page asks again. The customer's own only: someone
 * else's attempt, one that doesn't exist and an id that can't be one are all PAYMENT_NOT_FOUND.
 */
class GetPaymentResultUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetPaymentResultUc.Qry, PaymentResultDto, GetPaymentResultUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property paymentId the path's id, as it came.
     */
    data class Qry(val userId: UserId, val paymentId: String)

    override suspend fun execute(query: Qry): Result<PaymentResultDto, Failure> {
        val id = PaymentPublicId.of(query.paymentId)
        // An id from the path may be anything: only one that can be an id is written to the log.
        logger.info("GetPaymentResultUc.execute userId=${query.userId.value} paymentId=${id?.value ?: "(not an id)"}")
        if (id == null) return Result.failure(Failure.PaymentNotFound)
        return transactionManager.inTransaction {
            val payment = paymentRepository.find(id)?.takeIf { it.userId == query.userId } ?: return@inTransaction Result.failure(Failure.PaymentNotFound)
            val order = orderQmRepository.findOfCustomer(query.userId, OrderCodePolicy.codeOf(payment.orderId))
                ?: error("Payment ${payment.publicId.value} is of order ${payment.orderId.value}, which the read model doesn't have")
            // A method without a row (taken out of the settings since) keeps its code as its name.
            val methodName = shopSettingsRepository.get().shippingMethods.firstOrNull { it.code == order.shippingMethod }?.name ?: order.shippingMethod.name
            val succeeded = payment.status == PaymentStatus.SUCCEEDED || payment.status == PaymentStatus.REFUNDED
            Result.success(
                PaymentResultDto(
                    paymentId = payment.publicId.value,
                    outcome = payment.status.outcome,
                    amount = payment.amount,
                    gateway = payment.gateway,
                    refId = payment.refId?.value,
                    cardMask = payment.cardMask?.value,
                    paidOn = if (succeeded) payment.settledOn else null,
                    failureReason = payment.failureReason,
                    canRetry = PaymentPolicy.canRetry(payment.status, order.status, order.holdUntil, clock.instant()),
                    order = PaymentResultDto.Order(order.code.value, order.status, methodName, order.messenger.channel),
                ),
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object PaymentNotFound : Failure("PAYMENT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
