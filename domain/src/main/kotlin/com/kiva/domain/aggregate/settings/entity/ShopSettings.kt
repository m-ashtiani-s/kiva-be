package com.kiva.domain.aggregate.settings.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.under
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
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
 * The shop's own numbers: how it ships and what that costs, when shipping is free, which gateways it takes, how long an
 * order may wait for payment, how it is reached, and the marketing numbers. One set, edited whole by the panel's
 * settings page (`PUT /admin/settings`, [change]); V12 and V30 write the first one.
 *
 * Read on every request that needs it, never cached, so a change takes effect at once. What a change means for what
 * already happened is the callers' (roadmap phase 4, decision 5): each deadline is copied into the order or the
 * reservation that got it, and only the shipping methods' names and [lowStockThreshold] are read again everywhere.
 *
 * Rules it keeps itself ([change]): exactly the shipping methods and gateways the shop has (none added, none lost), at
 * least one of each switched on, the default shipping method and gateway switched on, exactly one default gateway, free
 * shipping only for methods the shop has. What it can't know: whether a gateway has an adapter (PaymentGateways).
 *
 * @property shippingMethods every method that has a row, active or not, in list order. A method without a row
 *   (COURIER for now) is never offered.
 * @property defaultShippingMethod a new cart's method.
 * @property freeShippingThreshold free shipping starts at this subtotal (after product discounts, before a code)...
 * @property freeShippingMethods ...and only for these methods (POST).
 * @property nonShippingWeekdays days nothing is shipped (Friday); they don't count as working days for delivery.
 * @property paymentPendingMinutes how long a placed order holds its stock while it waits for payment.
 * @property reservationEnabled the «رزرو ۴ روزه» switch is on offer at all.
 * @property reservationHoldDays how long a reservation holds a group of orders, from the first payment.
 * @property returnWindowDays how long after delivery a bag can be sent back.
 * @property lowStockThreshold fewer than this many left is «موجودی کم», with the number shown (StockPolicy).
 * @property preShipmentFeedbackHours how long a customer has to answer the photos before they count as approved.
 * @property paymentGateways every gateway that has a row, active or not, in list order.
 * @property supportPhone and [supportEmail], [supportHours], [social]: how the shop is reached, for the contact page.
 * @property siteStats the marketing numbers, typed by hand.
 * @property features the feature switches.
 * @property version how many times the set was saved: a save over another one that came first is refused.
 */
class ShopSettings private constructor(
    val shippingMethods: List<ShippingMethod>,
    val defaultShippingMethod: ShippingMethodCode,
    val freeShippingThreshold: Money,
    val freeShippingMethods: Set<ShippingMethodCode>,
    val nonShippingWeekdays: Set<DayOfWeek>,
    val paymentPendingMinutes: Int,
    val reservationEnabled: Boolean,
    val reservationHoldDays: Int,
    val returnWindowDays: Int,
    val lowStockThreshold: Int,
    val preShipmentFeedbackHours: Int,
    val paymentGateways: List<PaymentGatewayOption>,
    val supportPhone: SupportPhone?,
    val supportEmail: Email?,
    val supportHours: List<SupportHours>,
    val social: List<SocialLink>,
    val siteStats: SiteStats,
    val features: ShopFeatures,
    val version: Long,
) {
    // The same rules as shopsettings_tbl's CHECKs: a broken row is a bug, stop loudly.
    init {
        require(paymentPendingMinutes in PAYMENT_PENDING_MINUTES) { "Invalid paymentPendingMinutes $paymentPendingMinutes" }
        require(reservationHoldDays in RESERVATION_HOLD_DAYS) { "Invalid reservationHoldDays $reservationHoldDays" }
        require(returnWindowDays in RETURN_WINDOW_DAYS) { "Invalid returnWindowDays $returnWindowDays" }
        require(lowStockThreshold in LOW_STOCK_THRESHOLD) { "Invalid lowStockThreshold $lowStockThreshold" }
        require(preShipmentFeedbackHours in PRE_SHIPMENT_FEEDBACK_HOURS) { "Invalid preShipmentFeedbackHours $preShipmentFeedbackHours" }
        require(freeShippingThreshold.toman <= MAX_FREE_SHIPPING_THRESHOLD) { "Invalid freeShippingThreshold $freeShippingThreshold" }
        require(nonShippingWeekdays.size < DayOfWeek.entries.size) { "At least one day of the week must be left for shipping" }
        require(supportHours.size <= MAX_SUPPORT_HOURS && social.size <= MAX_SOCIAL_LINKS) { "Too many support hours or social links" }
        require(supportEmail == null || supportEmail.value.length <= MAX_SUPPORT_EMAIL_LENGTH) { "A support email longer than $MAX_SUPPORT_EMAIL_LENGTH" }
    }

    /**
     * The set the panel saved, all of it at once: every list replaces the old one, its order the new display order.
     * The shape of each value is already checked ([Change] holds value objects); here are the rules between them, every
     * problem at once, each under its field (`shippingMethods.2.code`, `paymentGateways`).
     *
     * The gateways keep their names and looks; only switching them, the default and the order change. The version
     * stays: the repository writes the new set only over the one this was read from.
     */
    fun change(change: Change): Result<ShopSettings, InputProblems> {
        val problems = shippingProblemsOf(change) + gatewayProblemsOf(change)
        if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))

        val gatewaysByCode = paymentGateways.associateBy { it.code }
        return Result.success(
            ShopSettings(
                shippingMethods = change.shippingMethods.mapIndexed { index, draft -> ShippingMethod.of(draft, sortOrder = index + 1) },
                defaultShippingMethod = change.defaultShippingMethod,
                freeShippingThreshold = change.freeShippingThreshold,
                freeShippingMethods = change.freeShippingMethods.toSet(),
                nonShippingWeekdays = change.nonShippingWeekdays,
                paymentPendingMinutes = change.paymentPendingMinutes,
                reservationEnabled = change.reservationEnabled,
                reservationHoldDays = change.reservationHoldDays,
                returnWindowDays = change.returnWindowDays,
                lowStockThreshold = change.lowStockThreshold,
                preShipmentFeedbackHours = change.preShipmentFeedbackHours,
                paymentGateways = change.paymentGateways.mapIndexed { index, draft ->
                    gatewaysByCode.getValue(draft.code).changed(draft.isActive, draft.isDefault, sortOrder = index + 1)
                },
                supportPhone = change.supportPhone,
                supportEmail = change.supportEmail,
                supportHours = change.supportHours,
                social = change.social,
                siteStats = change.siteStats,
                features = change.features,
                version = version,
            ),
        )
    }

    private fun shippingProblemsOf(change: Change): List<ResultFailure> {
        val ours = shippingMethods.map { it.code }.toSet()
        val sent = change.shippingMethods.map { it.code }
        val unknown = sent.withIndex().filter { it.value !in ours }.map { Problem.ShippingMethodNotSupported.under("code").under("${it.index}") }
        val listProblem = if (unknown.isEmpty() && (sent.size != ours.size || sent.toSet() != ours)) Problem.ShippingMethodListInvalid else null
        val active = change.shippingMethods.filter { it.isActive }.map { it.code }
        val methodProblems = listOfNotNull(
            if (unknown.isNotEmpty()) unknown.under("shippingMethods") else null,
            listProblem?.under("shippingMethods"),
            if (active.isEmpty()) Problem.NoActiveShippingMethod.under("shippingMethods") else null,
        )
        val defaultProblem = if (change.defaultShippingMethod !in active) Problem.DefaultShippingMethodInvalid.under("defaultShippingMethod") else null
        val freeUnknown = change.freeShippingMethods.withIndex().filter { it.value !in ours }.map { Problem.ShippingMethodNotSupported.under("${it.index}") }
        val freeProblem = if (freeUnknown.isNotEmpty()) freeUnknown.under("freeShippingMethods") else null
        return methodProblems + listOfNotNull(defaultProblem, freeProblem)
    }

    private fun gatewayProblemsOf(change: Change): List<ResultFailure> {
        val ours = paymentGateways.map { it.code }
        val sent = change.paymentGateways.map { it.code }
        if (sent.size != ours.size || sent.toSet() != ours.toSet()) return listOf(Problem.PaymentGatewayListInvalid.under("paymentGateways"))
        val defaults = change.paymentGateways.filter { it.isDefault }
        return listOfNotNull(
            if (change.paymentGateways.none { it.isActive }) Problem.NoActiveGateway.under("paymentGateways") else null,
            if (defaults.size != 1 || !defaults.single().isActive) Problem.DefaultGatewayInvalid.under("paymentGateways") else null,
        )
    }

    /** Every value is the same as [other]'s: saving this over [other] would change nothing. */
    fun sameValuesAs(other: ShopSettings): Boolean =
        shippingMethods.size == other.shippingMethods.size && shippingMethods.zip(other.shippingMethods).all { (a, b) -> a.sameValuesAs(b) } &&
            defaultShippingMethod == other.defaultShippingMethod && freeShippingThreshold == other.freeShippingThreshold &&
            freeShippingMethods == other.freeShippingMethods && nonShippingWeekdays == other.nonShippingWeekdays &&
            paymentPendingMinutes == other.paymentPendingMinutes && reservationEnabled == other.reservationEnabled &&
            reservationHoldDays == other.reservationHoldDays && returnWindowDays == other.returnWindowDays &&
            lowStockThreshold == other.lowStockThreshold && preShipmentFeedbackHours == other.preShipmentFeedbackHours &&
            paymentGateways.size == other.paymentGateways.size && paymentGateways.zip(other.paymentGateways).all { (a, b) -> a.sameValuesAs(b) } &&
            supportPhone == other.supportPhone && supportEmail == other.supportEmail && supportHours == other.supportHours &&
            social == other.social && siteStats == other.siteStats && features == other.features

    override fun toString(): String = "ShopSettings(version=$version, freeShippingThreshold=$freeShippingThreshold, reservationEnabled=$reservationEnabled)"

    /** The whole set the panel sent, every value already checked on its own (roadmap phase 4, decisions 1 to 6). */
    data class Change(
        val shippingMethods: List<ShippingMethodDraft>,
        val defaultShippingMethod: ShippingMethodCode,
        val freeShippingThreshold: Money,
        val freeShippingMethods: List<ShippingMethodCode>,
        val reservationEnabled: Boolean,
        val reservationHoldDays: Int,
        val returnWindowDays: Int,
        val lowStockThreshold: Int,
        val preShipmentFeedbackHours: Int,
        val paymentPendingMinutes: Int,
        val nonShippingWeekdays: Set<DayOfWeek>,
        val supportPhone: SupportPhone?,
        val supportEmail: Email?,
        val supportHours: List<SupportHours>,
        val social: List<SocialLink>,
        val paymentGateways: List<GatewayDraft>,
        val siteStats: SiteStats,
        val features: ShopFeatures,
    )

    /** One shipping method as the panel sent it; its place in the list is its new order. */
    data class ShippingMethodDraft(
        val code: ShippingMethodCode,
        val name: ShippingMethodName,
        val baseCost: Money,
        val deliveryDays: DeliveryDays,
        val icon: IconName,
        val isActive: Boolean,
        val trackingUrlTemplate: TrackingUrlTemplate?,
    )

    /** One gateway as the panel sent it: only its switches; its place in the list is its new order. */
    data class GatewayDraft(val code: PaymentGatewayCode, val isActive: Boolean, val isDefault: Boolean)

    // Each is put under its field by change(); the client reads the field from the path.
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object ShippingMethodNotSupported : Problem("SHIPPING_METHOD_NOT_SUPPORTED")

        data object ShippingMethodListInvalid : Problem("SHIPPING_METHOD_LIST_INVALID")

        data object NoActiveShippingMethod : Problem("NO_ACTIVE_SHIPPING_METHOD")

        data object DefaultShippingMethodInvalid : Problem("DEFAULT_SHIPPING_METHOD_INVALID")

        data object PaymentGatewayListInvalid : Problem("PAYMENT_GATEWAY_LIST_INVALID")

        data object NoActiveGateway : Problem("NO_ACTIVE_GATEWAY")

        data object DefaultGatewayInvalid : Problem("DEFAULT_GATEWAY_INVALID")
    }

    companion object {
        // The limits of roadmap phase 4, decision 4; the same as shopsettings_tbl's CHECKs.
        val PAYMENT_PENDING_MINUTES = 5..60
        val RESERVATION_HOLD_DAYS = 1..14
        val RETURN_WINDOW_DAYS = 0..60
        val LOW_STOCK_THRESHOLD = 1..20
        val PRE_SHIPMENT_FEEDBACK_HOURS = 1..168
        const val MAX_FREE_SHIPPING_THRESHOLD = 100_000_000L
        const val MAX_SHIPPING_COST = 10_000_000L
        const val MAX_SUPPORT_HOURS = 7
        const val MAX_SOCIAL_LINKS = 10
        const val MAX_SUPPORT_EMAIL_LENGTH = 100

        fun constructFromPersisted(
            shippingMethods: List<ShippingMethod>,
            defaultShippingMethod: ShippingMethodCode,
            freeShippingThreshold: Money,
            freeShippingMethods: Set<ShippingMethodCode>,
            nonShippingWeekdays: Set<DayOfWeek>,
            paymentPendingMinutes: Int,
            reservationEnabled: Boolean,
            reservationHoldDays: Int,
            returnWindowDays: Int,
            lowStockThreshold: Int,
            preShipmentFeedbackHours: Int,
            paymentGateways: List<PaymentGatewayOption>,
            supportPhone: SupportPhone?,
            supportEmail: Email?,
            supportHours: List<SupportHours>,
            social: List<SocialLink>,
            siteStats: SiteStats,
            features: ShopFeatures,
            version: Long,
        ): ShopSettings = ShopSettings(
            shippingMethods, defaultShippingMethod, freeShippingThreshold, freeShippingMethods, nonShippingWeekdays, paymentPendingMinutes,
            reservationEnabled, reservationHoldDays, returnWindowDays, lowStockThreshold, preShipmentFeedbackHours, paymentGateways,
            supportPhone, supportEmail, supportHours, social, siteStats, features, version,
        )
    }
}
