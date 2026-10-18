package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.dto.PreShipmentMediaDto
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId

/**
 * The photos before shipping of the customer's own order (`GET /me/orders/{orderCode}/media`), for the lightbox: the last
 * batch sent (never the operator's draft; the HTTP layer shows `sent` only), where the photos go, the customer's last
 * answer and until when they may give one. Someone else's order, or a code nobody has, is ORDER_NOT_FOUND.
 */
class GetMyOrderMediaUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderDetails: OrderDetails,
) : QueryUseCase<GetMyOrderMediaUc.Qry, GetMyOrderMediaUc.MyOrderMedia, GetMyOrderMediaUc.Failure> {

    private val logger = getLogger()

    data class Qry(val userId: UserId, val orderCode: String)

    /** The order's photos and its messenger. */
    data class MyOrderMedia(val photos: PreShipmentMediaDto, val messenger: OrderDetailDto.Messenger)

    override suspend fun execute(query: Qry): Result<MyOrderMedia, Failure> {
        val code = orderByCode.codeOf(query.orderCode)
        logger.info("GetMyOrderMediaUc.execute userId=${query.userId.value} orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        return transactionManager.inTransaction {
            val order = orderByCode.findOfCustomer(query.userId, query.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val photos = PreShipmentMediaDto.of(order, orderDetails.mediaOf(listOf(order.copy(photoDraft = emptyList()))))
            Result.success(MyOrderMedia(photos.copy(draft = emptyList()), order.messenger.let { OrderDetailDto.Messenger(it.channel, it.phone.value, it.username, it.note) }))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
