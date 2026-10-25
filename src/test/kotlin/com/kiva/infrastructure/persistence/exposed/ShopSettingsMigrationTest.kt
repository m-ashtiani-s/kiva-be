package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The rules of V12__shop_settings.sql: the database itself refuses settings that make no sense, whatever writes them
 * (SQL by hand today, the admin panel later). Every write here is rolled back, so the settings stay as they are for the
 * other tests.
 */
class ShopSettingsMigrationTest {

    private val db = TestDatabase.database

    private fun assertRejected(sql: String) = transaction(db) {
        assertFailsWith<ExposedSQLException> { exec(sql) }
        rollback()
    }

    private fun accepted(write: JdbcTransaction.() -> Unit) = transaction(db) {
        write()
        rollback()
    }

    @Test
    fun `a second settings row is refused`() = assertRejected(
        """
        INSERT INTO shopsettings_tbl ("freeShippingThreshold", "freeShippingMethods", "defaultShippingMethod", "nonShippingWeekdays",
                                      "paymentPendingMinutes", "reservationEnabled", "reservationHoldDays", "returnWindowDays", "updatedOn")
        VALUES (0, ARRAY ['POST'], 'POST', ARRAY ['FRIDAY'], 15, FALSE, 4, 7, now())
        """,
    )

    @Test
    fun `a method that takes fewer days at most than at least is refused`() =
        assertRejected("""UPDATE shippingmethod_tbl SET "minDays" = 6 WHERE code = 'POST'""")

    @Test
    fun `a second default gateway is refused`() = assertRejected(
        """UPDATE paymentgateway_tbl SET "isActive" = TRUE, "isDefault" = TRUE WHERE code = 'SAMAN'""",
    )

    @Test
    fun `the default gateway can't be switched off`() =
        assertRejected("""UPDATE paymentgateway_tbl SET "isActive" = FALSE WHERE code = 'ZARINPAL'""")

    @Test
    fun `an unknown method in freeShippingMethods is refused`() =
        assertRejected("""UPDATE shopsettings_tbl SET "freeShippingMethods" = ARRAY ['POST', 'BIKE']""")

    @Test
    fun `a default shipping method without a row is refused`() =
        assertRejected("""UPDATE shopsettings_tbl SET "defaultShippingMethod" = 'COURIER'""")

    @Test
    fun `a week with no shipping day at all is refused`() = assertRejected(
        """UPDATE shopsettings_tbl SET "nonShippingWeekdays" = ARRAY ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY']""",
    )

    @Test
    fun `a payment deadline outside 5 to 60 minutes is refused`() =
        assertRejected("""UPDATE shopsettings_tbl SET "paymentPendingMinutes" = 2""")

    @Test
    fun `a negative threshold or base cost is refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET "freeShippingThreshold" = -1""")
        assertRejected("""UPDATE shippingmethod_tbl SET "baseCost" = -1 WHERE code = 'TIPAX'""")
    }

    // --- V30: the panel's settings (phase 4) ---

    @Test
    fun `a low stock threshold outside 1 to 20 or a photo deadline outside 1 to 168 hours is refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET "lowStockThreshold" = 0""")
        assertRejected("""UPDATE shopsettings_tbl SET "lowStockThreshold" = 21""")
        assertRejected("""UPDATE shopsettings_tbl SET "preShipmentFeedbackHours" = 169""")
    }

    @Test
    fun `a support phone that isn't 11 digits from 0, or an email in upper case, is refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET "supportPhone" = '+982191001234'""")
        assertRejected("""UPDATE shopsettings_tbl SET "supportEmail" = 'Support@Kiva.ir'""")
    }

    @Test
    fun `more than 7 lines of support hours or 10 social links is refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET "supportHours" = (SELECT jsonb_agg('{}'::jsonb) FROM generate_series(1, 8))""")
        assertRejected("""UPDATE shopsettings_tbl SET social = (SELECT jsonb_agg('{}'::jsonb) FROM generate_series(1, 11))""")
    }

    @Test
    fun `features with a key left out, an unknown key or gift wrapping on are refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET features = '{"wishlistShare":true,"reviewMedia":false}'""")
        assertRejected("""UPDATE shopsettings_tbl SET features = '{"wishlistShare":true,"reviewMedia":false,"giftWrap":false,"darkMode":true}'""")
        assertRejected("""UPDATE shopsettings_tbl SET features = '{"wishlistShare":true,"reviewMedia":false,"giftWrap":true}'""")
    }

    @Test
    fun `a threshold above 100,000,000, a base cost above 10,000,000 or an icon in capitals is refused`() {
        assertRejected("""UPDATE shopsettings_tbl SET "freeShippingThreshold" = 100000001""")
        assertRejected("""UPDATE shippingmethod_tbl SET "baseCost" = 10000001 WHERE code = 'TIPAX'""")
        assertRejected("""UPDATE shippingmethod_tbl SET icon = 'Truck' WHERE code = 'TIPAX'""")
        assertRejected("""UPDATE shippingmethod_tbl SET name = repeat('ا', 51) WHERE code = 'TIPAX'""")
    }

    @Test
    fun `sensible changes are accepted (and rolled back)`() = accepted {
        exec("""UPDATE shopsettings_tbl SET "freeShippingThreshold" = 3200000, "freeShippingMethods" = ARRAY ['POST', 'TIPAX']""")
        exec("""UPDATE paymentgateway_tbl SET "isDefault" = FALSE WHERE code = 'ZARINPAL'""")
        exec("""UPDATE paymentgateway_tbl SET "isActive" = TRUE, "isDefault" = TRUE WHERE code = 'SAMAN'""")
        exec("""UPDATE shopsettings_tbl SET "lowStockThreshold" = 20, "preShipmentFeedbackHours" = 1, "supportPhone" = '02191001234', "supportEmail" = 'support@kiva.ir'""")
        exec("""UPDATE shopsettings_tbl SET features = '{"giftWrap":false,"reviewMedia":true,"wishlistShare":true}'""")
    }
}
