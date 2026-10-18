package com.kiva.application.usecase.order

import com.kiva.application.repository.AdminOrderFilter
import com.kiva.application.repository.AdminOrderSearch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.zip
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersian
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.refund.model.valueobject.RefundStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The panel queue's query parameters as they arrived (`GET /admin/orders`), the statuses already split at their commas.
 * An empty parameter is the same as a missing one.
 */
data class AdminOrderFilterQuery(
    val statuses: List<String> = emptyList(),
    val q: String? = null,
    val shippingMethod: String? = null,
    val reserved: String? = null,
    val awaitingPhotos: String? = null,
    val refundStatus: String? = null,
    val from: String? = null,
    val to: String? = null,
)

/**
 * Turns [AdminOrderFilterQuery] into a checked [AdminOrderFilter], every mistake at once, each under its parameter.
 *
 * `q` (at most [MAX_QUERY_LENGTH], Persian digits taken as Latin) is read by its shape: an order's code (`KV-218340`, or
 * 4 to 8 digits alone) looks for that order; a mobile number for the account's or the recipient's; anything else is
 * words. `from` and `to` are Tehran days, both kept whole; `from` after `to` is 400 on `to`.
 */
object AdminOrderFilters {

    /** From the contract's `q`. */
    const val MAX_QUERY_LENGTH = 100

    private val CODE_DIGITS = Regex("^[0-9]{4,8}$")
    private val CODE = Regex("^KV-[0-9]{4,8}$", RegexOption.IGNORE_CASE)

    fun parse(query: AdminOrderFilterQuery): Result<AdminOrderFilter, Invalid> = Result.zip(
        Result.zip(statusesOf(query.statuses), searchOf(query.q), shippingMethodOf(query.shippingMethod), ::InputProblems) { statuses, search, method ->
            Triple(statuses, search, method)
        },
        Result.zip(
            booleanOf(query.reserved, "reserved"), booleanOf(query.awaitingPhotos, "awaitingPhotos"), refundStatusOf(query.refundStatus), ::InputProblems,
        ) { reserved, awaiting, refund -> Triple(reserved, awaiting, refund) },
        Result.zip(dayOf(query.from, "from"), dayOf(query.to, "to"), ::InputProblems) { from, to -> from to to },
        { problems -> Invalid(problems.flattenGroups()) },
    ) { (statuses, search, method), (reserved, awaiting, refund), (from, to) ->
        if (from != null && to != null && from.isAfter(to)) return Result.failure(Invalid(listOf(Problem.DateRangeInvalid)))
        AdminOrderFilter(
            statuses = statuses,
            search = search,
            shippingMethod = method,
            reserved = reserved,
            awaitingPhotos = awaiting,
            refundStatus = refund,
            placedFrom = from?.atStartOfDay(ShopCalendar.ZONE)?.toInstant(),
            placedBefore = to?.plusDays(1)?.atStartOfDay(ShopCalendar.ZONE)?.toInstant(),
        )
    }

    private fun statusesOf(raws: List<String>): Result<Set<OrderStatus>?, ResultFailure> {
        val names = raws.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return Result.success(null)
        val statuses = names.map { name -> OrderStatus.entries.firstOrNull { it.name == name } ?: return Result.failure(Problem.StatusInvalid) }
        return Result.success(statuses.toSet())
    }

    private fun searchOf(raw: String?): Result<AdminOrderSearch?, ResultFailure> {
        val text = raw?.normalizePersian()?.ifEmpty { null } ?: return Result.success(null)
        if (text.length > MAX_QUERY_LENGTH) return Result.failure(Problem.SearchTooLong)
        val compact = text.normalizeDigits()
        return Result.success(
            when {
                CODE.matches(compact) -> AdminOrderSearch.Code(OrderCode.constructFromPersisted(compact.uppercase()))
                CODE_DIGITS.matches(compact) -> AdminOrderSearch.Code(OrderCode.constructFromPersisted("KV-$compact"))
                else -> IranianMobileNumber.makeNew(compact).getOrNull()?.let { AdminOrderSearch.Phone(it) }
                    ?: AdminOrderSearch.Words(text.lowercase().split(' ').filter { it.isNotEmpty() })
            },
        )
    }

    private fun shippingMethodOf(raw: String?): Result<ShippingMethodCode?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return ShippingMethodCode.entries.firstOrNull { it.name == text }?.let { Result.success(it) } ?: Result.failure(Problem.ShippingMethodInvalid)
    }

    private fun booleanOf(raw: String?, parameter: String): Result<Boolean?, ResultFailure> = when (raw?.trim()?.ifEmpty { null }) {
        null -> Result.success(null)
        "true" -> Result.success(true)
        "false" -> Result.success(false)
        else -> Result.failure(Problem.BooleanInvalid(parameter))
    }

    private fun refundStatusOf(raw: String?): Result<RefundStatus?, ResultFailure> = when (raw?.trim()?.ifEmpty { null }) {
        null -> Result.success(null)
        RefundStatus.PENDING.name -> Result.success(RefundStatus.PENDING)
        RefundStatus.PROCESSING.name -> Result.success(RefundStatus.PROCESSING)
        else -> Result.failure(Problem.RefundStatusInvalid)
    }

    private fun dayOf(raw: String?, parameter: String): Result<LocalDate?, ResultFailure> {
        val text = raw?.normalizeDigits()?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return try {
            Result.success(LocalDate.parse(text))
        } catch (_: DateTimeParseException) {
            Result.failure(Problem.DateInvalid(parameter))
        }
    }

    /** One or more parameters are wrong; each problem names its parameter. */
    data class Invalid(val problems: List<ResultFailure>) : ResultFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

    sealed class Problem(code: String, field: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object StatusInvalid : Problem("ORDER_STATUS_INVALID", "status")

        data object SearchTooLong : Problem("SEARCH_QUERY_TOO_LONG", "q", mapOf("maxLength" to MAX_QUERY_LENGTH))

        data object ShippingMethodInvalid : Problem("SHIPPING_METHOD_INVALID", "shippingMethod")

        data class BooleanInvalid(val parameter: String) : Problem("BOOLEAN_INVALID", parameter)

        data class DateInvalid(val parameter: String) : Problem("DATE_INVALID", parameter)

        data object DateRangeInvalid : Problem("DATE_RANGE_INVALID", "to")

        data object RefundStatusInvalid : Problem("REFUND_STATUS_FILTER_INVALID", "refundStatus")
    }
}
