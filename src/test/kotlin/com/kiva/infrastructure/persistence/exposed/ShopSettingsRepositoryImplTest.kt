package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.entity.ShopSettings
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
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reads the settings V12__shop_settings.sql and V30__admin_settings.sql write into the freshly migrated kiva_test (the
 * design's and contract's numbers), and saves them the way the panel does. A test that saves puts the settings back.
 */
class ShopSettingsRepositoryImplTest {

    private val db = TestDatabase.database
    private val settings = StoredSettings.read(db)
    private val repository = ShopSettingsRepositoryImpl()

    @AfterEach
    fun putTheSettingsBack() = StoredSettings.restore(db, settings)

    @Test
    fun `TIPAX 145,000 in 1 to 2 days and POST 75,000 in 3 to 5, both active, with their icons, and no COURIER`() {
        val methods = settings.shippingMethods.map { listOf(it.code, it.name, it.baseCost, it.deliveryDays.min, it.deliveryDays.max, it.icon, it.isActive) }

        assertEquals(
            listOf(
                listOf(ShippingMethodCode.TIPAX, "تیپاکس", Money.constructFromPersisted(145_000), 1, 2, "truck", true),
                listOf(ShippingMethodCode.POST, "پست معمولی", Money.constructFromPersisted(75_000), 3, 5, "box", true),
            ),
            methods,
        )
    }

    @Test
    fun `free shipping from 3,000,000 and only for POST, which is also a new cart's method`() {
        assertEquals(Money.constructFromPersisted(3_000_000), settings.freeShippingThreshold)
        assertEquals(setOf(ShippingMethodCode.POST), settings.freeShippingMethods)
        assertEquals(ShippingMethodCode.POST, settings.defaultShippingMethod)
    }

    @Test
    fun `no shipping on Friday, 15 minutes to pay, reservation on (since V19) with 4 days, 7 days to return`() {
        assertEquals(setOf(DayOfWeek.FRIDAY), settings.nonShippingWeekdays)
        assertEquals(15, settings.paymentPendingMinutes)
        assertEquals(true, settings.reservationEnabled)
        assertEquals(4, settings.reservationHoldDays)
        assertEquals(7, settings.returnWindowDays)
    }

    @Test
    fun `Zarinpal is the only active gateway and the default, Saman and Mellat are there switched off`() {
        val gateways = settings.paymentGateways

        assertEquals(listOf(PaymentGatewayCode.ZARINPAL, PaymentGatewayCode.SAMAN, PaymentGatewayCode.MELLAT), gateways.map { it.code })
        assertEquals(listOf(PaymentGatewayCode.ZARINPAL), gateways.filter { it.isActive }.map { it.code })
        assertEquals(listOf(PaymentGatewayCode.ZARINPAL), gateways.filter { it.isDefault }.map { it.code })
        val zarinpal = gateways.first()
        assertEquals(listOf("زرین‌پال", "#F2C230", "ز"), listOf(zarinpal.name, zarinpal.brandColor, zarinpal.initial))
        assertNull(zarinpal.logoUrl)
        assertEquals(listOf("بانک سامان", "بانک ملت"), gateways.drop(1).map { it.name })
    }

    @Test
    fun `V30 starts with 5 for low stock, 24 hours for the photos, no contact, no stats and every feature off`() {
        assertEquals(5, settings.lowStockThreshold)
        assertEquals(24, settings.preShipmentFeedbackHours)
        assertNull(settings.supportPhone)
        assertNull(settings.supportEmail)
        assertEquals(emptyList(), settings.supportHours)
        assertEquals(emptyList(), settings.social)
        assertEquals(SiteStats.NONE, settings.siteStats)
        assertEquals(ShopFeatures.NONE, settings.features)
        assertEquals("https://tipaxco.com/tracking?code=TX1", settings.shippingMethods.first().trackingUrlOf("TX1"))
    }

    @Test
    fun `a save keeps every value, its lists and documents, and counts the version up`() {
        val same = changeOf(settings)
        val change = same.copy(
            shippingMethods = same.shippingMethods.reversed().map { it.copy(isActive = it.code == ShippingMethodCode.POST) },
            freeShippingMethods = emptyList(),
            nonShippingWeekdays = setOf(DayOfWeek.FRIDAY, DayOfWeek.THURSDAY),
            lowStockThreshold = 3,
            preShipmentFeedbackHours = 48,
            supportPhone = SupportPhone.makeNew("02191001234").getOrNull(),
            supportEmail = Email.makeNew("support@kiva.ir").getOrNull(),
            supportHours = listOf(SupportHours.makeNew("شنبه تا چهارشنبه", listOf("SATURDAY", "WEDNESDAY"), "09:00", "21:00", false).getOrNull()!!),
            social = listOf(SocialLink.makeNew("TELEGRAM", "تلگرام", "https://t.me/kiva_shop", "@kiva_shop").getOrNull()!!),
            paymentGateways = listOf(
                ShopSettings.GatewayDraft(PaymentGatewayCode.MELLAT, isActive = true, isDefault = true),
                ShopSettings.GatewayDraft(PaymentGatewayCode.ZARINPAL, isActive = true, isDefault = false),
                ShopSettings.GatewayDraft(PaymentGatewayCode.SAMAN, isActive = false, isDefault = false),
            ),
            siteStats = SiteStats.makeNew(
                12_000, 48_000, 98, 60,
                Satisfaction.makeNew("4.9", 2480, listOf(Satisfaction.MetricInput("PHOTO_MATCH", 98)), listOf("TELEGRAM")),
            ).getOrNull()!!,
            features = ShopFeatures.makeNew(mapOf("wishlistShare" to true, "reviewMedia" to false, "giftWrap" to false)).getOrNull()!!,
        )
        val changed = settings.change(change).getOrNull()!!

        transaction(db) { repository.save(changed) }
        val stored = StoredSettings.read(db)

        assertTrue(stored.sameValuesAs(changed))
        assertEquals(settings.version + 1, stored.version)
        assertEquals(listOf(ShippingMethodCode.POST, ShippingMethodCode.TIPAX), stored.shippingMethods.map { it.code })
        assertEquals(listOf(PaymentGatewayCode.MELLAT, PaymentGatewayCode.ZARINPAL, PaymentGatewayCode.SAMAN), stored.paymentGateways.map { it.code })
        assertEquals(49, stored.siteStats.satisfaction?.averageRatingTenths)
        assertEquals("@kiva_shop", stored.social.single().handle)
    }

    @Test
    fun `a save over a version another save already moved writes nothing at all`() {
        val first = settings.change(changeOf(settings).copy(lowStockThreshold = 3)).getOrNull()!!
        val second = settings.change(changeOf(settings).copy(lowStockThreshold = 7, freeShippingThreshold = Money.constructFromPersisted(1))).getOrNull()!!

        transaction(db) { repository.save(first) }
        assertFailsWith<ConcurrentChangeException> { transaction(db) { repository.save(second) } }

        val stored = StoredSettings.read(db)
        assertEquals(3, stored.lowStockThreshold)
        assertEquals(settings.freeShippingThreshold, stored.freeShippingThreshold)
    }

    @Test
    fun `two saves at the same moment over one version - one is written, the other is refused, 10 times`() {
        repeat(10) { round ->
            val before = StoredSettings.read(db)
            val ready = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)
            val outcomes = (1..2).map { n ->
                pool.submit<Boolean> {
                    val mine = before.change(changeOf(before).copy(lowStockThreshold = n)).getOrNull()!!
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    try {
                        transaction(db) { repository.save(mine) }
                        true
                    } catch (e: ConcurrentChangeException) {
                        false
                    }
                }
            }.map { it.get(20, TimeUnit.SECONDS) }
            pool.shutdown()

            assertEquals(1, outcomes.count { it }, "round $round: $outcomes")
            assertEquals(before.version + 1, StoredSettings.read(db).version)
        }
    }

    /** A change that sends back exactly what [settings] holds. */
    private fun changeOf(settings: ShopSettings) = ShopSettings.Change(
        shippingMethods = settings.shippingMethods.map {
            ShopSettings.ShippingMethodDraft(
                it.code, ShippingMethodName.constructFromPersisted(it.name), it.baseCost, it.deliveryDays, IconName.constructFromPersisted(it.icon), it.isActive,
                it.trackingUrlTemplate,
            )
        },
        defaultShippingMethod = settings.defaultShippingMethod,
        freeShippingThreshold = settings.freeShippingThreshold,
        freeShippingMethods = settings.freeShippingMethods.toList(),
        reservationEnabled = settings.reservationEnabled,
        reservationHoldDays = settings.reservationHoldDays,
        returnWindowDays = settings.returnWindowDays,
        lowStockThreshold = settings.lowStockThreshold,
        preShipmentFeedbackHours = settings.preShipmentFeedbackHours,
        paymentPendingMinutes = settings.paymentPendingMinutes,
        nonShippingWeekdays = settings.nonShippingWeekdays,
        supportPhone = settings.supportPhone,
        supportEmail = settings.supportEmail,
        supportHours = settings.supportHours,
        social = settings.social,
        paymentGateways = settings.paymentGateways.map { ShopSettings.GatewayDraft(it.code, it.isActive, it.isDefault) },
        siteStats = settings.siteStats,
        features = settings.features,
    )
}
