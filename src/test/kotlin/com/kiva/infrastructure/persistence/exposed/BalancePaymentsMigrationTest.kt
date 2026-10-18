package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V29's walls, the last ones after the domain's rules (roadmap section 8.4): an order is paid once, but its balance may be
 * paid after each change; an attempt written before the migration pays the order; and an order that owes something never
 * ships, even in the read model.
 */
class BalancePaymentsMigrationTest {

    private val db = TestDatabase.database
    private val orderId = 987654330L

    @AfterEach
    fun removeRows() {
        transaction(db) {
            exec("""DELETE FROM payment_tbl WHERE "orderId" = $orderId""")
            exec("""DELETE FROM orderqm_tbl WHERE "orderId" = $orderId""")
        }
    }

    private var attempts = 0

    /** A confirmed attempt of the test's order; [purpose] left out is how every attempt before V29 was written. */
    private fun succeeded(purpose: String?) = transaction(db) {
        val n = ++attempts
        val id = "pay_" + n.toString().padStart(12, '0')
        exec(
            """
            INSERT INTO payment_tbl ("publicId", "orderId", "userId", gateway, amount, status, authority, "redirectUrl", "redirectMethod", "refId",
                "createdOn", "settledOn"${if (purpose != null) ", purpose" else ""})
            VALUES ('$id', $orderId, 7, 'ZARINPAL', 200000, 'SUCCEEDED', 'A$n', 'https://pay.example/$n', 'GET', 'R$n', now(), now()${if (purpose != null) ", '$purpose'" else ""})
            """.trimIndent(),
        )
    }

    private fun purposes(): List<String> = transaction(db) {
        exec("""SELECT purpose FROM payment_tbl WHERE "orderId" = $orderId ORDER BY "pId"""") { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            .orEmpty()
    }

    private fun constraintOf(e: ExposedSQLException): String? = (e.cause as? PSQLException)?.serverErrorMessage?.constraint

    @Test
    fun `an attempt written without a purpose pays the order`() {
        succeeded(purpose = null)

        assertEquals(listOf("ORDER"), purposes())
    }

    @Test
    fun `a second successful ORDER payment of one order is refused`() {
        succeeded("ORDER")

        val refused = assertFailsWith<ExposedSQLException> { succeeded("ORDER") }

        assertEquals("payment_one_order_success_idx", constraintOf(refused))
    }

    @Test
    fun `a paid order's balance may be paid after each change - two BALANCE successes beside the ORDER one`() {
        succeeded("ORDER")
        succeeded("BALANCE")
        succeeded("BALANCE")

        assertEquals(listOf("ORDER", "BALANCE", "BALANCE"), purposes())
    }

    @Test
    fun `a purpose that isn't one is refused`() {
        assertFailsWith<ExposedSQLException> { succeeded("TIP") }
    }

    private fun insertOrder(status: String, balanceDue: Long, shipped: Boolean) = transaction(db) {
        exec(
            """
            INSERT INTO orderqm_tbl ("orderId", code, "userId", status, "placedOn", "paidOn", "holdUntil", payable, "itemsCount",
                "shippingMethod", "messengerChannel", "reserveRequested", lines, address, messenger, totals, timeline, "updatedOn",
                "trackingCode", "shippedOn", "balanceDue")
            VALUES ($orderId, 'KV-987654', 7, '$status', now(), now(), now() + interval '15 minutes', 1450000, 1, 'POST', 'TELEGRAM', false, '[{}]', '{}', '{}', '{}', '[{}]', now(),
                ${if (shipped) "'TPX4821760', now()" else "NULL, NULL"}, $balanceDue)
            """.trimIndent(),
        )
    }

    @Test
    fun `an order that owes something can't be SHIPPED in the read model`() {
        val refused = assertFailsWith<ExposedSQLException> { insertOrder("SHIPPED", balanceDue = 200_000, shipped = true) }

        assertEquals("orderqm_no_ship_with_balance", constraintOf(refused))
    }

    @Test
    fun `owing while being prepared is fine, and shipped owing nothing is fine`() {
        insertOrder("PROCESSING", balanceDue = 200_000, shipped = false)
        removeRows()
        insertOrder("SHIPPED", balanceDue = 0, shipped = true)
    }

    @Test
    fun `a negative balance is refused`() {
        assertFailsWith<ExposedSQLException> { insertOrder("PROCESSING", balanceDue = -1, shipped = false) }
    }
}
