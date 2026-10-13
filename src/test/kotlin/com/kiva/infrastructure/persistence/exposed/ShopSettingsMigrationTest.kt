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

    @Test
    fun `sensible changes are accepted (and rolled back)`() = accepted {
        exec("""UPDATE shopsettings_tbl SET "freeShippingThreshold" = 3200000, "freeShippingMethods" = ARRAY ['POST', 'TIPAX']""")
        exec("""UPDATE paymentgateway_tbl SET "isDefault" = FALSE WHERE code = 'ZARINPAL'""")
        exec("""UPDATE paymentgateway_tbl SET "isActive" = TRUE, "isDefault" = TRUE WHERE code = 'SAMAN'""")
    }
}
