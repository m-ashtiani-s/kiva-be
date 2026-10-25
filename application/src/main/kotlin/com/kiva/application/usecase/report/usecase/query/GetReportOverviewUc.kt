package com.kiva.application.usecase.report.usecase.query

import com.kiva.application.dto.ReportOverviewDto
import com.kiva.application.repository.DayTotals
import com.kiva.application.repository.ReportQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * The panel's report of a range of days (`GET /admin/reports/overview`, ADMIN only; roadmap section 9.3).
 *
 * `from` and `to` are Tehran days, both whole: `from` from its 00:00 to the end of `to`. Both must come; `from` after `to`,
 * or more than [MAX_DAYS] days counting both ends, is 400 on `to` (decision 38). Every number is a sum the database makes
 * (ReportQmRepository); this use case only divides, in whole numbers: shares in tenths of a percent, a half up, and the
 * average order to the nearest toman, a half up. `changeRequestRate` is what `mediaApprovalRate` leaves of 100, so the two
 * always add up (decision 34). `returnRate` is 0 until returns exist (phase 7).
 */
class GetReportOverviewUc(
    private val transactionManager: TransactionManager,
    private val reportQmRepository: ReportQmRepository,
) : QueryUseCase<GetReportOverviewUc.Qry, ReportOverviewDto, GetReportOverviewUc.Failure> {

    private val logger = getLogger()

    /** `from` and `to` as the query string had them; null when not given. */
    data class Qry(val from: String?, val to: String?)

    override suspend fun execute(query: Qry): Result<ReportOverviewDto, Failure> {
        logger.info("GetReportOverviewUc.execute from=${query.from} to=${query.to}")
        val (from, to) = Result.zip(dayOf(query.from, "from"), dayOf(query.to, "to"), Failure::InvalidInput) { from, to -> from to to }
            .unwrapOr { return Result.failure(it) }
        if (from.isAfter(to)) return Result.failure(Failure.InvalidInput(listOf(DateRangeInvalid)))
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) return Result.failure(Failure.InvalidInput(listOf(DateRangeTooLong)))

        val start = from.atStartOfDay(ShopCalendar.ZONE).toInstant()
        val until = to.plusDays(1).atStartOfDay(ShopCalendar.ZONE).toInstant()
        return transactionManager.inTransaction {
            val totals = reportQmRepository.periodTotals(start, until)
            val daily = reportQmRepository.dailyTotals(start, until, ShopCalendar.ZONE)
            val photos = reportQmRepository.photoOutcomes(start, until)
            val approval = perMille(photos.sent - photos.changeRequested, photos.sent)
            Result.success(
                ReportOverviewDto(
                    from = from,
                    to = to,
                    revenue = totals.revenue,
                    ordersCount = totals.ordersCount,
                    averageOrderValue = averageOf(totals.revenue, totals.ordersCount),
                    newCustomers = reportQmRepository.newCustomers(start, until),
                    reservationSharePerMille = perMille(totals.inReservation, totals.ordersCount),
                    mediaApprovalRatePerMille = approval,
                    changeRequestRatePerMille = if (photos.sent == 0) 0 else FULL - approval,
                    returnRatePerMille = 0,
                    discountTotal = totals.discountTotal,
                    shippingMix = ShippingMethodCode.entries.associateWith { totals.byShippingMethod[it] ?: 0 },
                    messengerMix = MessengerChannel.entries.associateWith { totals.byMessenger[it] ?: 0 },
                    topProducts = reportQmRepository.topProducts(start, until, TOP_PRODUCTS).map(ReportOverviewDto.TopProduct::from),
                    dailySeries = daysOf(from, to).map { day ->
                        val ofDay = daily[day] ?: NO_ORDERS
                        ReportOverviewDto.Day(day, ofDay.revenue, ofDay.orders)
                    },
                ),
            )
        }
    }

    private fun dayOf(raw: String?, field: String): Result<LocalDate, ResultFailure> {
        val text = raw?.normalizeDigits()?.trim()?.ifEmpty { null } ?: return Result.failure(FieldRequired(field))
        return try {
            Result.success(LocalDate.parse(text))
        } catch (_: DateTimeParseException) {
            Result.failure(DateInvalid(field))
        }
    }

    private fun daysOf(from: LocalDate, to: LocalDate): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    /** `from` or `to` didn't come. */
    data class FieldRequired(val name: String) : ResultFailure("FIELD_REQUIRED", FailureKind.INVALID, field = name)

    /** `from` or `to` isn't a `YYYY-MM-DD` day. */
    data class DateInvalid(val name: String) : ResultFailure("DATE_INVALID", FailureKind.INVALID, field = name)

    /** `from` is after `to`. */
    data object DateRangeInvalid : ResultFailure("DATE_RANGE_INVALID", FailureKind.INVALID, field = "to")

    /** More than [MAX_DAYS] days, both ends counted. */
    data object DateRangeTooLong : ResultFailure("DATE_RANGE_TOO_LONG", FailureKind.INVALID, field = "to", meta = mapOf("maxDays" to MAX_DAYS))

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    companion object {
        /** The longest range: a whole year, a leap one too. */
        const val MAX_DAYS = 366

        /** How many best sellers the report lists. */
        const val TOP_PRODUCTS = 5

        /** 100%, in tenths of a percent. */
        private const val FULL = 1000

        private val NO_ORDERS = DayTotals(Money.ZERO, 0)

        /** [part] of [whole] in tenths of a percent, a half up; 0 of nothing is 0. */
        fun perMille(part: Int, whole: Int): Int {
            if (whole == 0) return 0
            return ((part.toLong() * 2 * FULL + whole) / (2L * whole)).toInt()
        }

        /** [revenue] shared by [orders], to the nearest toman, a half up; nothing without orders. */
        fun averageOf(revenue: Money, orders: Int): Money {
            if (orders == 0) return Money.ZERO
            return Money.constructFromPersisted(Math.addExact(Math.multiplyExact(revenue.toman, 2), orders.toLong()) / (2L * orders))
        }
    }
}
