package com.kiva.application.usecase.settings

import com.kiva.application.port.AuditChange
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.product.usecase.command.RecordingAuditTrail
import com.kiva.application.usecase.settings.usecase.command.UpdateSettingsUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.application.util.factory.ShopSettingsFactory
import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.patch.Patch
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateSettingsUcTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC)
    private val transactions = MockTransactionManager()
    private val repository = ShopSettingsFactory.InMemory()
    private val audit = RecordingAuditTrail()
    private val useCase = UpdateSettingsUc(transactions, repository, audit, clock)
    private val actor = PanelActor(UserId.constructFromPersisted(7), "5.160.12.34")

    /** The form exactly as the panel would send back what [settings] holds. */
    private fun fieldsOf(settings: ShopSettings) = ShopSettingsFields(
        shippingMethods = settings.shippingMethods.map {
            ShopSettingsFields.ShippingMethod(
                it.code.name, it.name, it.baseCost.toman, it.deliveryDays.min, it.deliveryDays.max, it.icon, it.isActive, it.trackingUrlTemplate?.value,
            )
        },
        defaultShippingMethod = settings.defaultShippingMethod.name,
        freeShippingThreshold = settings.freeShippingThreshold.toman,
        freeShippingMethods = settings.freeShippingMethods.map { it.name },
        reservationEnabled = settings.reservationEnabled,
        reservationHoldDays = settings.reservationHoldDays,
        returnWindowDays = settings.returnWindowDays,
        lowStockThreshold = settings.lowStockThreshold,
        preShipmentFeedbackHours = settings.preShipmentFeedbackHours,
        paymentPendingMinutes = settings.paymentPendingMinutes,
        nonShippingWeekdays = settings.nonShippingWeekdays.map { it.name },
        supportPhone = Patch.Set(settings.supportPhone?.value),
        supportEmail = Patch.Set(settings.supportEmail?.value),
        supportHours = emptyList(),
        social = emptyList(),
        paymentGateways = settings.paymentGateways.map { ShopSettingsFields.Gateway(it.code.name, it.isActive, it.isDefault) },
        siteStats = ShopSettingsFields.Stats(0, 0, 0, 0, Patch.Set(null)),
        features = mapOf("wishlistShare" to false, "reviewMedia" to false, "giftWrap" to false),
    )

    private val same = fieldsOf(repository.settings)

    private fun pathsOf(failure: ResultFailure?, parent: String? = null): List<String> {
        if (failure == null) return emptyList()
        val path = listOfNotNull(parent, failure.field).joinToString(".").ifEmpty { null }
        if (failure.causes.isNotEmpty()) return failure.causes.flatMap { pathsOf(it, path) }
        return listOf(listOfNotNull(path, failure.code).joinToString(" "))
    }

    @Test
    fun `the same values again save nothing and log nothing`() = runTest {
        val result = useCase.execute(UpdateSettingsUc.Cmd(same, actor))

        assertTrue(result.isSuccess)
        assertEquals(0, repository.saves)
        assertEquals(emptyList(), audit.recorded)
        assertEquals(1, transactions.transactionsOpened)
    }

    @Test
    fun `a change is saved once and logged once, with only the fields that changed`() = runTest {
        val fields = same.copy(
            shippingMethods = same.shippingMethods!!.map { if (it.code == "TIPAX") it.copy(baseCost = 160_000) else it },
            lowStockThreshold = 3,
            supportPhone = Patch.Set("021-91001234"),
        )

        val result = useCase.execute(UpdateSettingsUc.Cmd(fields, actor))

        assertTrue(result.isSuccess)
        assertEquals(1, repository.saves)
        assertEquals(3, repository.settings.lowStockThreshold)
        assertEquals(1L, repository.settings.version)
        val entry = audit.recorded.single()
        assertEquals(AuditAction.SETTINGS_UPDATED, entry.action)
        assertEquals(AuditEntityType.SETTINGS, entry.entityType)
        assertEquals("shop", entry.entityId)
        assertNull(entry.entityLabel)
        assertEquals(
            listOf(
                AuditChange("shippingMethods.TIPAX.baseCost", "145000", "160000"),
                AuditChange("lowStockThreshold", "5", "3"),
                AuditChange("supportPhone", null, "02191001234"),
            ),
            entry.changes,
        )
    }

    @Test
    fun `every limit of decision 4 outside its range is 400 on its own path, all at once, and opens no transaction`() = runTest {
        val methods = same.shippingMethods!!
        val fields = same.copy(
            shippingMethods = listOf(methods[0].copy(baseCost = 10_000_001, minDays = 31, maxDays = 31, name = "", icon = "Truck"), methods[1].copy(maxDays = 2)),
            freeShippingThreshold = 100_000_001,
            reservationHoldDays = 15,
            returnWindowDays = 61,
            lowStockThreshold = 0,
            preShipmentFeedbackHours = 169,
            paymentPendingMinutes = 4,
            nonShippingWeekdays = listOf("SATURDAY", "SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"),
            supportHours = List(8) { ShopSettingsFields.Hours("x", listOf("FRIDAY"), null, null, null) },
            social = List(11) { ShopSettingsFields.Social("TELEGRAM", "x", "https://t.me/x", null) },
            siteStats = ShopSettingsFields.Stats(-1, 0, 101, 0, Patch.Set(null)),
        )

        val failure = useCase.execute(UpdateSettingsUc.Cmd(fields, actor)).failureOrNull()

        assertEquals(
            listOf(
                "shippingMethods.0.name FIELD_REQUIRED", "shippingMethods.0.baseCost NUMBER_OUT_OF_RANGE", "shippingMethods.0.minDays NUMBER_OUT_OF_RANGE",
                "shippingMethods.0.maxDays NUMBER_OUT_OF_RANGE", "shippingMethods.0.icon ICON_INVALID", "shippingMethods.1.maxDays NUMBER_OUT_OF_RANGE",
                "freeShippingThreshold NUMBER_OUT_OF_RANGE", "reservationHoldDays NUMBER_OUT_OF_RANGE", "returnWindowDays NUMBER_OUT_OF_RANGE",
                "lowStockThreshold NUMBER_OUT_OF_RANGE", "preShipmentFeedbackHours NUMBER_OUT_OF_RANGE", "paymentPendingMinutes NUMBER_OUT_OF_RANGE",
                "nonShippingWeekdays NON_SHIPPING_WEEKDAYS_INVALID", "supportHours TOO_MANY_ITEMS", "social TOO_MANY_ITEMS",
                "siteStats.happyCustomers NUMBER_OUT_OF_RANGE", "siteStats.photoMatchSatisfactionPercent NUMBER_OUT_OF_RANGE",
            ),
            pathsOf(failure),
        )
        assertEquals("VALIDATION_ERROR", failure?.code)
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `every top-level field left out is FIELD_REQUIRED on it, null phone and email clear them`() = runTest {
        val empty = ShopSettingsFields(
            null, null, null, null, null, null, null, null, null, null, null, Patch.Unchanged, Patch.Unchanged, null, null, null, null, null,
        )

        assertEquals(
            listOf(
                "shippingMethods", "defaultShippingMethod", "freeShippingThreshold", "freeShippingMethods", "reservationEnabled", "reservationHoldDays",
                "returnWindowDays", "lowStockThreshold", "preShipmentFeedbackHours", "paymentPendingMinutes", "nonShippingWeekdays", "supportPhone",
                "supportEmail", "supportHours", "social", "paymentGateways", "siteStats", "features",
            ).map { "$it FIELD_REQUIRED" },
            pathsOf(useCase.execute(UpdateSettingsUc.Cmd(empty, actor)).failureOrNull()),
        )
        assertTrue(useCase.execute(UpdateSettingsUc.Cmd(same.copy(supportPhone = Patch.Set(null), supportEmail = Patch.Set(" ")), actor)).isSuccess)
    }

    @Test
    fun `the lists' items are checked under their index`() = runTest {
        val fields = same.copy(
            shippingMethods = same.shippingMethods!!.map { it.copy(code = if (it.code == "POST") "CAMEL" else it.code, trackingUrlTemplate = "http://x/{code}") },
            freeShippingMethods = listOf("POST", "POST"),
            nonShippingWeekdays = listOf("FRIDAY", "JOMEH"),
            supportPhone = Patch.Set("12345"),
            supportEmail = Patch.Set("a".repeat(95) + "@x.com"),
            supportHours = listOf(ShopSettingsFields.Hours("x", listOf("FRIDAY"), null, null, null), ShopSettingsFields.Hours("", null, null, null, null)),
            social = listOf(ShopSettingsFields.Social("TELEGRAM", "x", "ftp://x", null)),
            paymentGateways = listOf(ShopSettingsFields.Gateway("PAYPAL", null, true)),
            features = mapOf("giftWrap" to true, "wishlistShare" to false, "reviewMedia" to false),
        )

        assertEquals(
            listOf(
                "shippingMethods.0.trackingUrlTemplate TRACKING_URL_TEMPLATE_INVALID", "shippingMethods.1.code SHIPPING_METHOD_INVALID",
                "shippingMethods.1.trackingUrlTemplate TRACKING_URL_TEMPLATE_INVALID", "freeShippingMethods.1 LIST_ITEM_DUPLICATE",
                "nonShippingWeekdays.1 WEEKDAY_INVALID", "supportPhone SUPPORT_PHONE_INVALID", "supportEmail EMAIL_TOO_LONG", "supportHours.1 SUPPORT_HOURS_INVALID",
                "social.0 SOCIAL_LINK_INVALID", "paymentGateways.0.code PAYMENT_GATEWAY_INVALID", "paymentGateways.0.isActive FIELD_REQUIRED",
                "features.giftWrap FEATURE_NOT_AVAILABLE",
            ),
            pathsOf(useCase.execute(UpdateSettingsUc.Cmd(fields, actor)).failureOrNull()),
        )
    }

    @Test
    fun `a rule between fields is 400 too, and nothing is saved`() = runTest {
        val fields = same.copy(shippingMethods = same.shippingMethods!!.map { if (it.code == "POST") it.copy(isActive = false) else it })

        val failure = useCase.execute(UpdateSettingsUc.Cmd(fields, actor)).failureOrNull()

        assertEquals(listOf("defaultShippingMethod DEFAULT_SHIPPING_METHOD_INVALID"), pathsOf(failure))
        assertEquals(0, repository.saves)
        assertEquals(emptyList(), audit.recorded)
    }

    @Test
    fun `a save another one beat is CHANGED_BY_SOMEONE_ELSE for the HTTP layer, never tried again`() = runTest {
        val beaten = object : com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository by repository {
            override fun save(settings: ShopSettings) = throw ConcurrentChangeException(
                com.kiva.common.event.valueobject.EventAggregateName("SHOP_SETTINGS"), com.kiva.common.event.valueobject.EventAggregateId("shop"),
            )
        }
        val useCase = UpdateSettingsUc(transactions, beaten, audit, clock)

        assertThrows<ConcurrentChangeException> { useCase.execute(UpdateSettingsUc.Cmd(same.copy(lowStockThreshold = 4), actor)) }
        assertEquals(1, transactions.transactionsOpened)
        assertEquals(emptyList(), audit.recorded)
    }
}
