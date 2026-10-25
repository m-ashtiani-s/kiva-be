package com.kiva.application.usecase.tracking.usecase.query

import com.kiva.application.dto.MyTrackingDayDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * «رهگیری‌های من» (`GET /me/tracking`): the customer's own shipped orders, not cancelled, by the day they left (Tehran),
 * the latest day first; at most [MAX_CODES] of them. Each with its carrier and the page that follows it.
 */
class ListMyTrackingUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<ListMyTrackingUc.Qry, List<MyTrackingDayDto>, Nothing> {

    private val logger = getLogger()

    /** [userId] from the access token. */
    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<List<MyTrackingDayDto>, Nothing> {
        logger.info("ListMyTrackingUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            val methods = shopSettingsRepository.get().shippingMethods.associateBy { it.code }
            val days = orderQmRepository.findShippedOfCustomer(query.userId, MAX_CODES)
                .groupBy { ShopCalendar.dateOf(it.shipment!!.shippedOn) }
                .map { (date, orders) ->
                    MyTrackingDayDto(
                        date,
                        orders.map { order ->
                            val code = order.shipment!!.trackingCode.value
                            val method = methods[order.shippingMethod]
                            MyTrackingDayDto.Shipment(order.code, order.shippingMethod, method?.name ?: order.shippingMethod.name, code, method?.trackingUrlOf(code))
                        },
                    )
                }
            Result.success(days)
        }
    }

    companion object {
        const val MAX_CODES = 50
    }
}
