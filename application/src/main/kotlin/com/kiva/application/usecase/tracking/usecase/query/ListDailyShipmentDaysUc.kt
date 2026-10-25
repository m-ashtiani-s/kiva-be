package com.kiva.application.usecase.tracking.usecase.query

import com.kiva.application.dto.DailyShipmentDayDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The daily list's tabs (`GET /tracking/daily/days`, public): today and the [Qry.days] − 1 days before it, in Tehran,
 * newest first; each with how many parcels left that day (tracking codes: a reservation group's orders are one) and
 * whether the shop ships on that weekday. `days` 1 to [MAX_DAYS], 10 by default.
 */
class ListDailyShipmentDaysUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<ListDailyShipmentDaysUc.Qry, List<DailyShipmentDayDto>, ListDailyShipmentDaysUc.Failure> {

    private val logger = getLogger()

    /** [days] as the query string had it; null for the default. */
    data class Qry(val days: String?)

    override suspend fun execute(query: Qry): Result<List<DailyShipmentDayDto>, Failure> {
        logger.info("ListDailyShipmentDaysUc.execute days=${query.days}")
        val days = query.days?.trim()?.let { raw -> raw.toIntOrNull()?.takeIf { it in 1..MAX_DAYS } ?: return Result.failure(Failure.invalidDays()) } ?: DEFAULT_DAYS

        return transactionManager.inTransaction {
            val today = ShopCalendar.dateOf(clock.instant())
            val dates = (0 until days).map { today.minusDays(it.toLong()) }
            val from = dates.last().atStartOfDay(ShopCalendar.ZONE).toInstant()
            val until = today.plusDays(1).atStartOfDay(ShopCalendar.ZONE).toInstant()
            val codesByDay = orderQmRepository.findInDailyList(from, until)
                .groupBy({ ShopCalendar.dateOf(it.shipment!!.shippedOn) }, { it.shipment!!.trackingCode })
            val settings = shopSettingsRepository.get()
            Result.success(dates.map { DailyShipmentDayDto(it, codesByDay[it].orEmpty().distinct().size, it.dayOfWeek in settings.nonShippingWeekdays) })
        }
    }

    companion object {
        const val DEFAULT_DAYS = 10
        const val MAX_DAYS = 30
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        companion object {
            fun invalidDays(): Failure = InvalidInput(listOf(FieldFailure("days", Problem.DaysInvalid)))
        }
    }

    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object DaysInvalid : Problem("DAYS_INVALID", mapOf("max" to MAX_DAYS))
    }
}
