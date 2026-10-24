package com.kiva.application.usecase.tracking.usecase.query

import com.kiva.application.dto.DailyShipmentDto
import com.kiva.application.dto.DailyShipmentListDto
import com.kiva.application.repository.OrderQm
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.tracking.DailyShipmentMask
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * One day of the public daily list (`GET /tracking/daily`): the parcels that left on [Qry.date] (Tehran) and are listed
 * daily, one row per tracking code (a reservation group's orders are one parcel), the latest first. Only the last
 * [ListDailyShipmentDaysUc.MAX_DAYS] days: an older date or one in the future is an empty list. Every recipient is
 * masked here (DailyShipmentMask); no whole name or number leaves this use case.
 *
 * `q` (at most [MAX_QUERY] characters): four digits are the end of the recipient's phone, one letter the first letter
 * of their name, anything else part of the city's name. A day's parcels are few (one shop), so they are read whole and
 * filtered and paged here.
 */
class ListDailyShipmentsUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<ListDailyShipmentsUc.Qry, DailyShipmentListDto, ListDailyShipmentsUc.Failure> {

    private val logger = getLogger()

    /** The query string as it came; [q] may be a piece of a phone number, so only its length is logged. */
    data class Qry(val date: String?, val q: String?, val pageable: Pageable) {
        override fun toString(): String = "Qry(date=$date, q=${q?.length?.let { "($it chars)" }}, page=${pageable.page})"
    }

    override suspend fun execute(query: Qry): Result<DailyShipmentListDto, Failure> {
        logger.info("ListDailyShipmentsUc.execute $query")
        val problems = mutableListOf<ResultFailure>()
        val date = try {
            LocalDate.parse(query.date?.trim().orEmpty())
        } catch (e: DateTimeParseException) {
            null.also { problems += FieldFailure("date", Problem.DateInvalid) }
        }
        val q = query.q?.trim()?.normalizeDigits()?.ifEmpty { null }
        if (q != null && q.length > MAX_QUERY) problems += FieldFailure("q", Problem.QueryTooLong)
        if (problems.isNotEmpty()) return Result.failure(Failure.InvalidInput(problems))
        val day = date!!

        return transactionManager.inTransaction {
            val settings = shopSettingsRepository.get()
            val today = ShopCalendar.dateOf(clock.instant())
            val inWindow = !day.isAfter(today) && day.isAfter(today.minusDays(ListDailyShipmentDaysUc.MAX_DAYS.toLong()))
            val parcels = if (!inWindow) {
                emptyList()
            } else {
                val from = day.atStartOfDay(ShopCalendar.ZONE).toInstant()
                val until = day.plusDays(1).atStartOfDay(ShopCalendar.ZONE).toInstant()
                orderQmRepository.findInDailyList(from, until)
                    // A deleted account's parcel has no recipient to mask: it leaves the list (roadmap phase 14).
                    .filterNot { it.address.isErased }
                    .groupBy { it.shipment!!.trackingCode }
                    .values
                    .map { orders -> parcelOf(orders, settings) }
                    .filter { q == null || it.matches(q) }
            }
            val page = parcels.drop(query.pageable.offset.toInt()).take(query.pageable.size)
            Result.success(DailyShipmentListDto(day, day.dayOfWeek in settings.nonShippingWeekdays, Page(page, query.pageable.page, query.pageable.size, parcels.size.toLong())))
        }
    }

    /** One row for the parcel [orders] left in (one customer's, to one address): its first order speaks for it. */
    private fun parcelOf(orders: List<OrderQm>, settings: ShopSettings): DailyShipmentDto {
        val first = orders.minBy { it.orderId.value }
        val parcel = first.shipment!!
        val method = settings.shippingMethods.firstOrNull { it.code == first.shippingMethod }
        val name = checkNotNull(first.address.recipientName).value
        val phone = checkNotNull(first.address.recipientPhone)
        return DailyShipmentDto(
            id = first.orderId.value,
            recipientMasked = DailyShipmentMask.name(name),
            recipientInitial = DailyShipmentMask.initial(name),
            city = first.address.cityName,
            carrier = first.shippingMethod,
            carrierName = method?.name ?: first.shippingMethod.name,
            phoneLast4 = DailyShipmentMask.phoneLast4(phone),
            phoneMasked = DailyShipmentMask.phone(phone),
            trackingCode = parcel.trackingCode.value,
            trackingUrl = method?.trackingUrlOf(parcel.trackingCode.value),
            shippedOn = parcel.shippedOn,
        )
    }

    private fun DailyShipmentDto.matches(q: String): Boolean = when {
        q.length == 4 && q.all(Char::isDigit) -> phoneLast4 == q
        q.length == 1 && q.single().isLetter() -> recipientInitial == q
        else -> city.contains(q)
    }

    companion object {
        const val MAX_QUERY = 20
    }

    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object DateInvalid : Problem("DATE_INVALID")

        data object QueryTooLong : Problem("TRACKING_QUERY_TOO_LONG", mapOf("maxLength" to MAX_QUERY))
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
