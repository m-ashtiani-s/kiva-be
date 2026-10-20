package com.kiva.application.usecase.settings

import com.kiva.common.patch.Patch
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TooManyItems
import com.kiva.common.railway.bind
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.required
import com.kiva.common.railway.requiredIn
import com.kiva.common.railway.under
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.Satisfaction
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodName
import com.kiva.domain.aggregate.settings.model.valueobject.ShopFeatures
import com.kiva.domain.aggregate.settings.model.valueobject.SiteStats
import com.kiva.domain.aggregate.settings.model.valueobject.SocialLink
import com.kiva.domain.aggregate.settings.model.valueobject.SupportHours
import com.kiva.domain.aggregate.settings.model.valueobject.SupportPhone
import com.kiva.domain.aggregate.settings.model.valueobject.TrackingUrlTemplate
import com.kiva.domain.aggregate.user.model.valueobject.Email
import java.time.DayOfWeek

/**
 * The contract's AdminSettings as `PUT /admin/settings` sent it, every field raw: the whole set, every top-level field
 * required (roadmap phase 4, decision 1). [supportPhone] and [supportEmail] are required but may be null, so they tell
 * a field left out (FIELD_REQUIRED) from one sent as null (cleared); the same for `siteStats.satisfaction`.
 * The read-only fields of the contract (a method's description, a gateway's name and `available`) never get here.
 */
data class ShopSettingsFields(
    val shippingMethods: List<ShippingMethod>?,
    val defaultShippingMethod: String?,
    val freeShippingThreshold: Long?,
    val freeShippingMethods: List<String>?,
    val reservationEnabled: Boolean?,
    val reservationHoldDays: Int?,
    val returnWindowDays: Int?,
    val lowStockThreshold: Int?,
    val preShipmentFeedbackHours: Int?,
    val paymentPendingMinutes: Int?,
    val nonShippingWeekdays: List<String>?,
    val supportPhone: Patch<String?>,
    val supportEmail: Patch<String?>,
    val supportHours: List<Hours>?,
    val social: List<Social>?,
    val paymentGateways: List<Gateway>?,
    val siteStats: Stats?,
    val features: Map<String, Boolean?>?,
) {
    data class ShippingMethod(
        val code: String?,
        val name: String?,
        val baseCost: Long?,
        val minDays: Int?,
        val maxDays: Int?,
        val icon: String?,
        val isActive: Boolean?,
        val trackingUrlTemplate: String?,
    )

    data class Hours(val label: String?, val weekdays: List<String>?, val from: String?, val to: String?, val messengerOnly: Boolean?)

    data class Social(val channel: String?, val name: String?, val url: String?, val handle: String?)

    data class Gateway(val code: String?, val isActive: Boolean?, val isDefault: Boolean?)

    data class Stats(
        val happyCustomers: Int?,
        val preShipmentPhotos: Int?,
        val photoMatchSatisfactionPercent: Int?,
        val productModels: Int?,
        val satisfaction: Patch<SatisfactionSummary?>,
    )

    /** @property averageRating the number exactly as the client wrote it (`4.9`), never through a Double. */
    data class SatisfactionSummary(val averageRating: String?, val totalCount: Int?, val metrics: List<Satisfaction.MetricInput>?, val sources: List<String?>?)

    /** Every problem of the form at once, each under its full path (`shippingMethods.1.maxDays`, `social.0`). */
    fun check(): Result<ShopSettings.Change, InputProblems> {
        val problems = Problems()
        val methods = problems.take(itemsOf(shippingMethods, "shippingMethods") { index, method -> methodOf(method).mapOnFailure { it.under("$index") } })
        val defaultMethod = problems.take(defaultShippingMethod.required().bind { ShippingMethodCode.makeNew(it) }.forField("defaultShippingMethod"))
        val threshold = problems.take(
            freeShippingThreshold.requiredIn(0..ShopSettings.MAX_FREE_SHIPPING_THRESHOLD).mapOnSuccess(Money::constructFromPersisted).forField("freeShippingThreshold"),
        )
        val freeMethods = problems.take(distinctListOf(freeShippingMethods, "freeShippingMethods") { ShippingMethodCode.makeNew(it) })
        val reservation = problems.take(reservationEnabled.required().forField("reservationEnabled"))
        val holdDays = problems.take(reservationHoldDays.requiredIn(ShopSettings.RESERVATION_HOLD_DAYS).forField("reservationHoldDays"))
        val returnDays = problems.take(returnWindowDays.requiredIn(ShopSettings.RETURN_WINDOW_DAYS).forField("returnWindowDays"))
        val lowStock = problems.take(lowStockThreshold.requiredIn(ShopSettings.LOW_STOCK_THRESHOLD).forField("lowStockThreshold"))
        val feedbackHours = problems.take(preShipmentFeedbackHours.requiredIn(ShopSettings.PRE_SHIPMENT_FEEDBACK_HOURS).forField("preShipmentFeedbackHours"))
        val pendingMinutes = problems.take(paymentPendingMinutes.requiredIn(ShopSettings.PAYMENT_PENDING_MINUTES).forField("paymentPendingMinutes"))
        val weekdays = problems.take(weekdaysOf(nonShippingWeekdays))
        val phone = problems.take(phoneOf(supportPhone))
        val email = problems.take(emailOf(supportEmail))
        val hours = problems.take(
            itemsOf(supportHours, "supportHours", ShopSettings.MAX_SUPPORT_HOURS) { index, it ->
                SupportHours.makeNew(it.label, it.weekdays, it.from, it.to, it.messengerOnly).mapOnFailure { failure -> failure.under("$index") }
            },
        )
        val links = problems.take(
            itemsOf(social, "social", ShopSettings.MAX_SOCIAL_LINKS) { index, it ->
                SocialLink.makeNew(it.channel, it.name, it.url, it.handle).mapOnFailure { failure -> failure.under("$index") }
            },
        )
        val gateways = problems.take(itemsOf(paymentGateways, "paymentGateways") { index, gateway -> gatewayOf(gateway).mapOnFailure { it.under("$index") } })
        val stats = problems.take(statsOf(siteStats))
        val switches = problems.take(features.required().forField("features").bind { ShopFeatures.makeNew(it).mapOnFailure { failure -> failure.under("features") } })
        if (problems.any()) return Result.failure(problems.all())

        return Result.success(
            ShopSettings.Change(
                shippingMethods = methods.value,
                defaultShippingMethod = defaultMethod.value,
                freeShippingThreshold = threshold.value,
                freeShippingMethods = freeMethods.value,
                reservationEnabled = reservation.value,
                reservationHoldDays = holdDays.value,
                returnWindowDays = returnDays.value,
                lowStockThreshold = lowStock.value,
                preShipmentFeedbackHours = feedbackHours.value,
                paymentPendingMinutes = pendingMinutes.value,
                nonShippingWeekdays = weekdays.value,
                supportPhone = phone.value,
                supportEmail = email.value,
                supportHours = hours.value,
                social = links.value,
                paymentGateways = gateways.value,
                siteStats = stats.value,
                features = switches.value,
            ),
        )
    }

    private fun methodOf(method: ShippingMethod): Result<ShopSettings.ShippingMethodDraft, InputProblems> {
        val problems = Problems()
        val code = problems.take(method.code.required().bind { ShippingMethodCode.makeNew(it) }.forField("code"))
        val name = problems.take(method.name.required().bind { ShippingMethodName.makeNew(it) }.forField("name"))
        val cost = problems.take(method.baseCost.requiredIn(0..ShopSettings.MAX_SHIPPING_COST).mapOnSuccess(Money::constructFromPersisted).forField("baseCost"))
        val days = problems.take(DeliveryDays.makeNew(method.minDays, method.maxDays))
        val icon = problems.take(method.icon.required().bind { IconName.makeNew(it) }.forField("icon"))
        val active = problems.take(method.isActive.required().forField("isActive"))
        val tracking = problems.take(method.trackingUrlTemplate?.let { TrackingUrlTemplate.makeNew(it) } ?: Result.success(null))
        if (problems.any()) return Result.failure(problems.all())
        return Result.success(ShopSettings.ShippingMethodDraft(code.value, name.value, cost.value, days.value, icon.value, active.value, tracking.value))
    }

    private fun gatewayOf(gateway: Gateway): Result<ShopSettings.GatewayDraft, InputProblems> {
        val problems = Problems()
        val code = problems.take(gateway.code.required().bind { PaymentGatewayCode.makeNew(it) }.forField("code"))
        val active = problems.take(gateway.isActive.required().forField("isActive"))
        val default = problems.take(gateway.isDefault.required().forField("isDefault"))
        if (problems.any()) return Result.failure(problems.all())
        return Result.success(ShopSettings.GatewayDraft(code.value, active.value, default.value))
    }

    private fun weekdaysOf(raw: List<String>?): Result<Set<DayOfWeek>, ResultFailure> =
        distinctListOf(raw, "nonShippingWeekdays") { name ->
            DayOfWeek.entries.firstOrNull { it.name == name }?.let { Result.success(it) } ?: Result.failure(Problem.WeekdayInvalid)
        }.bind { days ->
            if (days.size < DayOfWeek.entries.size) Result.success(days.toSet()) else Result.failure(Problem.NoShippingDayLeft.under("nonShippingWeekdays"))
        }

    // A blank text from a form is a field cleared, as null is.
    private fun phoneOf(raw: Patch<String?>): Result<SupportPhone?, ResultFailure> = when (raw) {
        Patch.Unchanged -> Result.failure(FieldRequired.under("supportPhone"))
        is Patch.Set -> raw.value?.takeIf { it.isNotBlank() }?.let { SupportPhone.makeNew(it) } ?: Result.success(null)
    }

    private fun emailOf(raw: Patch<String?>): Result<Email?, ResultFailure> = when (raw) {
        Patch.Unchanged -> Result.failure(FieldRequired.under("supportEmail"))
        is Patch.Set -> {
            val text = raw.value
            when {
                text.isNullOrBlank() -> Result.success(null)
                text.trim().length > ShopSettings.MAX_SUPPORT_EMAIL_LENGTH -> Result.failure(Problem.EmailTooLong)
                else -> Email.makeNew(text).forField("supportEmail")
            }
        }
    }

    private fun statsOf(raw: Stats?): Result<SiteStats, ResultFailure> {
        if (raw == null) return Result.failure(FieldRequired.under("siteStats"))
        val satisfaction: Result<Satisfaction?, ResultFailure> = when (val summary = raw.satisfaction) {
            Patch.Unchanged -> Result.failure(FieldRequired.under("satisfaction"))
            is Patch.Set -> summary.value?.let {
                Satisfaction.makeNew(it.averageRating, it.totalCount, it.metrics, it.sources).mapOnFailure { failure -> failure.under("satisfaction") }
            } ?: Result.success(null)
        }
        return SiteStats.makeNew(raw.happyCustomers, raw.preShipmentPhotos, raw.photoMatchSatisfactionPercent, raw.productModels, satisfaction)
            .mapOnFailure { it.under("siteStats") }
    }

    /** Every item checked, each item's problems under its index; at most [maxItems] (TOO_MANY_ITEMS on the list). */
    private fun <T, R> itemsOf(raw: List<T>?, field: String, maxItems: Int = Int.MAX_VALUE, check: (Int, T) -> Result<R, ResultFailure>): Result<List<R>, ResultFailure> {
        if (raw == null) return Result.failure(FieldRequired.under(field))
        if (raw.size > maxItems) return Result.failure(TooManyItems(maxItems).under(field))
        val results = raw.mapIndexed(check)
        val failures = results.mapNotNull { it.failureOrNull() }
        if (failures.isNotEmpty()) return Result.failure(failures.under(field))
        return Result.success(results.map { (it as Result.Success).value })
    }

    /** A list of plain values: each checked under its index, and an item the second time LIST_ITEM_DUPLICATE. */
    private fun <T> distinctListOf(raw: List<String>?, field: String, check: (String) -> Result<T, ResultFailure>): Result<List<T>, ResultFailure> =
        itemsOf(raw, field) { index, item ->
            if (raw.orEmpty().indexOf(item) != index) Result.failure(ListItemDuplicate.under("$index")) else check(item).mapOnFailure { it.under("$index") }
        }

    sealed class Problem(code: String, field: String? = null, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object WeekdayInvalid : Problem("WEEKDAY_INVALID")

        data object NoShippingDayLeft : Problem("NON_SHIPPING_WEEKDAYS_INVALID")

        data object EmailTooLong : Problem("EMAIL_TOO_LONG", "supportEmail", mapOf("maxLength" to ShopSettings.MAX_SUPPORT_EMAIL_LENGTH))
    }
}
