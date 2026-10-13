package com.kiva.application.usecase.checkout.usecase.query

import com.kiva.application.dto.PaymentInitDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.repository.PaymentRepository

/**
 * Where the shopper goes to pay an attempt whose session is open (the contract's PaymentInit): the `payment` of placing
 * an order's answer, and of a retry's. Asked after StartPaymentUc opened the session, so the attempt has its redirect;
 * someone else's attempt is PAYMENT_NOT_FOUND.
 */
class GetPaymentInitUc(
    private val transactionManager: TransactionManager,
    private val paymentRepository: PaymentRepository,
    private val orderQmRepository: OrderQmRepository,
) : QueryUseCase<GetPaymentInitUc.Qry, PaymentInitDto, GetPaymentInitUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token, never from the request. */
    data class Qry(val userId: UserId, val paymentId: PaymentPublicId)

    override suspend fun execute(query: Qry): Result<PaymentInitDto, Failure> {
        logger.info("GetPaymentInitUc.execute userId=${query.userId.value} paymentId=${query.paymentId.value}")
        return transactionManager.inTransaction {
            val payment = paymentRepository.find(query.paymentId)?.takeIf { it.userId == query.userId }
                ?: return@inTransaction Result.failure(Failure.PaymentNotFound)
            val order = orderQmRepository.findOfCustomer(query.userId, OrderCodePolicy.codeOf(payment.orderId))
                ?: error("Payment ${payment.publicId.value} is of order ${payment.orderId.value}, which the read model doesn't have")
            val redirect = payment.redirect ?: error("Payment ${payment.publicId.value} has no session yet (${payment.status})")
            Result.success(PaymentInitDto(payment.publicId.value, payment.gateway, payment.amount, redirect, order.holdUntil))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object PaymentNotFound : Failure("PAYMENT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
