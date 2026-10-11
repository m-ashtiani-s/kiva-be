package com.kiva.domain.aggregate.order

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShippingMethod
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.DeliveryDays
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.TIPAX
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Ready-made values for the pricing and shipping tests: the shop as V12__shop_settings.sql sets it up, and the prototype's bags. */
object PricingFixtures {

    /** Thursday 2026-10-15 (۲۳ مهر), 23:59 in Tehran. */
    val thursdayNight: Instant = Instant.parse("2026-10-15T20:29:00Z")

    /** 2026-10-15T21:00Z: still Thursday in UTC, but Friday 2026-10-16, 00:30 in Tehran. */
    val fridayJustAfterMidnight: Instant = Instant.parse("2026-10-15T21:00:00Z")

    /** Sunday 2026-10-11 (۱۹ مهر), 10:00 in Tehran: the roadmap's «امروز». */
    val sundayMorning: Instant = Instant.parse("2026-10-11T06:30:00Z")

    fun toman(amount: Long): Money = Money.constructFromPersisted(amount)

    fun date(text: String): LocalDate = LocalDate.parse(text)

    fun days(min: Int, max: Int): DeliveryDays = DeliveryDays.constructFromPersisted(min, max)

    fun line(price: Long, compareAt: Long? = null, quantity: Int = 1, purchasable: Boolean = true): PricedLine = PricedLine(
        priceTag = PriceTag.constructFromPersisted(toman(price), compareAt?.let(::toman)),
        quantity = Quantity.constructFromPersisted(quantity),
        purchasable = purchasable,
    )

    // The bags of the golden examples (roadmap section 7.5), with the prototype's prices.
    /** کیف دستی آیدا، کاراملی: 2,790,000 instead of 3,290,000. */
    val aidaCaramel: PricedLine = line(2_790_000, compareAt = 3_290_000)

    /** KV-1015، قهوه‌ای: 690,000 instead of 790,000. */
    val kv1015Brown: PricedLine = line(690_000, compareAt = 790_000)

    /** کراس‌بادی نیلا: 1,450,000 instead of 1,750,000. */
    val nila: PricedLine = line(1_450_000, compareAt = 1_750_000)

    fun tipax(isActive: Boolean = true, sortOrder: Int = 1, baseCost: Long = 145_000): ShippingMethod =
        ShippingMethod.constructFromPersisted(TIPAX, "تیپاکس", toman(baseCost), days(1, 2), "truck", isActive, sortOrder)

    fun post(isActive: Boolean = true, sortOrder: Int = 2): ShippingMethod =
        ShippingMethod.constructFromPersisted(POST, "پست معمولی", toman(75_000), days(3, 5), "box", isActive, sortOrder)

    /** The shop's settings as V12__shop_settings.sql writes them; each test changes only what it is about. */
    fun shopSettings(
        shippingMethods: List<ShippingMethod> = listOf(tipax(), post()),
        defaultShippingMethod: ShippingMethodCode = POST,
        freeShippingThreshold: Long = 3_000_000,
        freeShippingMethods: Set<ShippingMethodCode> = setOf(POST),
        nonShippingWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.FRIDAY),
        reservationHoldDays: Int = 4,
    ): ShopSettings = ShopSettings.constructFromPersisted(
        shippingMethods = shippingMethods,
        defaultShippingMethod = defaultShippingMethod,
        freeShippingThreshold = toman(freeShippingThreshold),
        freeShippingMethods = freeShippingMethods,
        nonShippingWeekdays = nonShippingWeekdays,
        paymentPendingMinutes = 15,
        reservationEnabled = false,
        reservationHoldDays = reservationHoldDays,
        returnWindowDays = 7,
        paymentGateways = listOf(
            PaymentGatewayOption.constructFromPersisted(PaymentGatewayCode.ZARINPAL, "زرین‌پال", "#F2C230", "ز", null, isActive = true, isDefault = true, sortOrder = 1),
        ),
    )
}
