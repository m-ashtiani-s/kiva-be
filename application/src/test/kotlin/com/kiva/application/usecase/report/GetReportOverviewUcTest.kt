package com.kiva.application.usecase.report

import com.kiva.application.repository.DayTotals
import com.kiva.application.repository.PeriodTotals
import com.kiva.application.repository.PhotoOutcomes
import com.kiva.application.repository.ReportQmRepository
import com.kiva.application.repository.TopProductQm
import com.kiva.application.usecase.report.usecase.query.GetReportOverviewUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals

/**
 * What the report's query does around the database's sums (roadmap section 9.3): the days it asks for, the days it
 * refuses, the shares and the average in whole numbers, and the days and keys it fills in with zeros.
 */
class GetReportOverviewUcTest {

    /** Answers with what each test sets, and remembers the range it was asked for. */
    private class FixedReport : ReportQmRepository {
        var totals = PeriodTotals.NONE
        var daily = emptyMap<LocalDate, DayTotals>()
        var photos = PhotoOutcomes(0, 0)
        var asked: Pair<Instant, Instant>? = null

        override fun periodTotals(from: Instant, until: Instant): PeriodTotals {
            asked = from to until
            return totals
        }

        override fun dailyTotals(from: Instant, until: Instant, zone: ZoneId) = daily
        override fun newCustomers(from: Instant, until: Instant) = 0
        override fun photoOutcomes(from: Instant, until: Instant) = photos
        override fun topProducts(from: Instant, until: Instant, limit: Int) = emptyList<TopProductQm>()
    }

    private val report = FixedReport()
    private val useCase = GetReportOverviewUc(MockTransactionManager(), report)

    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    private suspend fun problemsOf(from: String?, to: String?): List<String> =
        useCase.execute(GetReportOverviewUc.Qry(from, to)).failureOrNull()?.causes.orEmpty().map { "${it.field} ${it.code}" }

    @Test
    fun `from and to are whole Tehran days - from 00 00 of the first to 00 00 after the last, Persian digits too`() = runTest {
        useCase.execute(GetReportOverviewUc.Qry("2026-10-15", "۲۰۲۶-۱۰-۱۶"))

        // Tehran is UTC+03:30 all year.
        assertEquals(Instant.parse("2026-10-14T20:30:00Z") to Instant.parse("2026-10-16T20:30:00Z"), report.asked)
    }

    @Test
    fun `both days must come - FIELD_REQUIRED on each, together`() = runTest {
        assertEquals(listOf("from FIELD_REQUIRED", "to FIELD_REQUIRED"), problemsOf(null, " "))
    }

    @Test
    fun `a day that isn't YYYY-MM-DD is DATE_INVALID on its own field`() = runTest {
        assertEquals(listOf("from DATE_INVALID", "to DATE_INVALID"), problemsOf("1405-07-30x", "2026-02-30"))
    }

    @Test
    fun `from after to is DATE_RANGE_INVALID on to - the same day is fine`() = runTest {
        assertEquals(listOf("to DATE_RANGE_INVALID"), problemsOf("2026-10-16", "2026-10-15"))
        assertEquals(emptyList(), problemsOf("2026-10-15", "2026-10-15"))
    }

    @Test
    fun `366 days with both ends is the longest - 367 or 400 is DATE_RANGE_TOO_LONG on to with maxDays`() = runTest {
        // 2028 is a leap year: its 366 days fit.
        assertEquals(emptyList(), problemsOf("2028-01-01", "2028-12-31"))
        assertEquals(listOf("to DATE_RANGE_TOO_LONG"), problemsOf("2028-01-01", "2029-01-01"))
        assertEquals(listOf("to DATE_RANGE_TOO_LONG"), problemsOf("2026-01-01", "2027-02-04"))

        val failure = useCase.execute(GetReportOverviewUc.Qry("2026-01-01", "2027-02-04")).failureOrNull()
        assertEquals(mapOf("maxDays" to 366), failure?.causes?.single()?.meta)
    }

    @Test
    fun `every day of the range in order, a day without orders as zero`() = runTest {
        report.daily = mapOf(LocalDate.parse("2026-10-17") to DayTotals(toman(900_000), 2))

        val days = useCase.execute(GetReportOverviewUc.Qry("2026-10-15", "2026-10-17")).getOrNull()!!.dailySeries

        assertEquals(
            listOf("2026-10-15 0 0", "2026-10-16 0 0", "2026-10-17 900000 2"),
            days.map { "${it.date} ${it.revenue.toman} ${it.orders}" },
        )
    }

    @Test
    fun `every carrier and messenger is there, those nobody used as zero`() = runTest {
        report.totals = PeriodTotals(1, toman(100), Money.ZERO, 0, mapOf(ShippingMethodCode.POST to 1), mapOf(MessengerChannel.BALE to 1))

        val overview = useCase.execute(GetReportOverviewUc.Qry("2026-10-15", "2026-10-15")).getOrNull()!!

        assertEquals(mapOf(ShippingMethodCode.TIPAX to 0, ShippingMethodCode.POST to 1, ShippingMethodCode.COURIER to 0), overview.shippingMix)
        assertEquals(mapOf(MessengerChannel.RUBIKA to 0, MessengerChannel.TELEGRAM to 0, MessengerChannel.BALE to 1), overview.messengerMix)
    }

    @Test
    fun `the two photo rates always add up to 100 - 1 of 16 is 6_3 and 93_7, not 93_8`() = runTest {
        report.photos = PhotoOutcomes(sent = 16, changeRequested = 15)

        val overview = useCase.execute(GetReportOverviewUc.Qry("2026-10-15", "2026-10-15")).getOrNull()!!

        assertEquals(63, overview.mediaApprovalRatePerMille)
        assertEquals(937, overview.changeRequestRatePerMille)
    }

    @Test
    fun `nothing in the range - every share and the average are 0, never a division by zero`() = runTest {
        val overview = useCase.execute(GetReportOverviewUc.Qry("2026-10-15", "2026-10-15")).getOrNull()!!

        assertEquals(
            listOf(0, 0, 0, 0),
            listOf(overview.reservationSharePerMille, overview.mediaApprovalRatePerMille, overview.changeRequestRatePerMille, overview.returnRatePerMille),
        )
        assertEquals(Money.ZERO, overview.averageOrderValue)
    }

    @Test
    fun `shares are tenths of a percent, a half up`() {
        assertEquals(125, GetReportOverviewUc.perMille(1, 8))
        assertEquals(333, GetReportOverviewUc.perMille(1, 3))
        assertEquals(667, GetReportOverviewUc.perMille(2, 3))
        // 1 of 16 is 62.5‰: up to 63.
        assertEquals(63, GetReportOverviewUc.perMille(1, 16))
        assertEquals(1000, GetReportOverviewUc.perMille(7, 7))
        assertEquals(0, GetReportOverviewUc.perMille(0, 0))
    }

    @Test
    fun `the average order is to the nearest toman, a half up`() {
        assertEquals(toman(1_631_250), GetReportOverviewUc.averageOf(toman(13_049_997), 8))
        assertEquals(toman(2), GetReportOverviewUc.averageOf(toman(3), 2))
        assertEquals(toman(1), GetReportOverviewUc.averageOf(toman(4), 3))
        assertEquals(Money.ZERO, GetReportOverviewUc.averageOf(Money.ZERO, 0))
    }
}
