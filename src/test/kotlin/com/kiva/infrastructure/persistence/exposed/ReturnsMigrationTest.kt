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
 * V32's walls, the last ones after the ReturnRequest aggregate's rules (roadmap section 12.5): each CHECK refuses a row
 * that breaks it, and refund_tbl takes a return's refund only with its source, one that isn't FAILED at a time, while the
 * refunds of phase 2 (a cancellation's, staff's by hand) go in as before.
 */
class ReturnsMigrationTest {

    private val db = TestDatabase.database
    private val orderId = 987_654_500L

    @AfterEach
    fun removeRows() {
        transaction(db) {
            exec("""DELETE FROM returnrequest_tbl WHERE "orderId" = $orderId""")
            exec("""DELETE FROM refund_tbl WHERE "orderId" = $orderId""")
        }
    }

    private var returns = 0

    /**
     * A return of the test's order: REQUESTED with nothing else unless [columns] (SQL column names to SQL values) say
     * otherwise.
     */
    private fun insertReturn(columns: Map<String, String> = emptyMap()) = transaction(db) {
        val n = ++returns
        val all = linkedMapOf(
            "\"returnId\"" to "${900_000 + n}", "code" to "'RT-${901_000 + n}'", "\"orderId\"" to "$orderId", "\"orderCode\"" to "'KV-654500'",
            "\"userId\"" to "7", "status" to "'REQUESTED'", "reason" to "'NOT_AS_PICTURED'", "items" to "'[{\"lineId\": 1, \"quantity\": 1}]'",
            "\"refundMethod\"" to "'ORIGINAL_PAYMENT'", "\"shippingPaidBy\"" to "'KIVA'", "\"createdOn\"" to "now()",
        )
        all.putAll(columns)
        exec("INSERT INTO returnrequest_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})")
    }

    private fun refusedBy(columns: Map<String, String>): String? {
        val refused = assertFailsWith<ExposedSQLException>("$columns") { insertReturn(columns) }
        return (refused.cause as? PSQLException)?.serverErrorMessage?.constraint
    }

    private val decided = mapOf("\"decidedOn\"" to "now()")

    @Test
    fun `a REQUESTED return, and each later status with what it needs, go in`() {
        insertReturn()
        removeRows()
        insertReturn(decided + mapOf("status" to "'REJECTED'", "\"rejectionReason\"" to "'عکس‌ها مشکلی نشون نمی‌دن'"))
        insertReturn(decided + mapOf("status" to "'PICKUP_SCHEDULED'", "\"pickupCarrier\"" to "'TIPAX'", "\"pickupTracking\"" to "'TPX4821760'"))
    }

    @Test
    fun `REJECTED without its reason, or a reason without REJECTED, is refused`() {
        assertEquals("returnrequest_rejected_check", refusedBy(decided + mapOf("status" to "'REJECTED'")))
        assertEquals("returnrequest_rejected_check", refusedBy(decided + mapOf("status" to "'APPROVED'", "\"rejectionReason\"" to "'نه'")))
    }

    @Test
    fun `CLOSED without when or why, REFUNDED without its refund, PICKUP_SCHEDULED without a carrier, RECEIVED without its receipt are refused`() {
        assertEquals("returnrequest_closed_check", refusedBy(decided + mapOf("status" to "'CLOSED'", "\"closedOn\"" to "now()")))
        assertEquals(
            "returnrequest_refunded_check",
            refusedBy(decided + mapOf("status" to "'REFUNDED'", "\"refundedOn\"" to "now()", "receipt" to "'{}'", "\"receivedOn\"" to "now()")),
        )
        assertEquals("returnrequest_pickup_check", refusedBy(decided + mapOf("status" to "'PICKUP_SCHEDULED'")))
        assertEquals("returnrequest_receipt_check", refusedBy(decided + mapOf("status" to "'RECEIVED'", "\"receivedOn\"" to "now()")))
    }

    @Test
    fun `a status past REQUESTED without a decision is refused`() {
        assertEquals("returnrequest_decided_check", refusedBy(mapOf("status" to "'APPROVED'")))
    }

    @Test
    fun `an unknown status, reason, payer, carrier or method, a bad code, no line, 7 media or a lowercase tracking code are refused`() {
        listOf(
            mapOf("status" to "'LOST'"), mapOf("reason" to "'TOO_BIG'"), mapOf("\"shippingPaidBy\"" to "'BOTH'"),
            decided + mapOf("status" to "'PICKUP_SCHEDULED'", "\"pickupCarrier\"" to "'COURIER'"), mapOf("\"refundMethod\"" to "'STORE_CREDIT'"),
            mapOf("code" to "'RT-12'"), mapOf("items" to "'[]'"), mapOf("\"mediaIds\"" to "'[1,2,3,4,5,6,7]'"),
            decided + mapOf("status" to "'PICKUP_SCHEDULED'", "\"pickupCarrier\"" to "'POST'", "\"pickupTracking\"" to "'tpx48217'"),
        ).forEach { columns -> assertFailsWith<ExposedSQLException>("$columns") { insertReturn(columns) } }
    }

    private var refunds = 0

    private fun insertRefund(source: String, returnId: Long?, status: String = "PENDING") = transaction(db) {
        val n = ++refunds
        val failedOn = if (status == "FAILED") "now()" else "NULL"
        exec(
            """
            INSERT INTO refund_tbl ("refundId", "orderId", amount, method, status, source, "returnId", "expectedBy", "createdOn", "createdBy", "failedOn")
            VALUES (${900_000 + n}, $orderId, 100000, 'ORIGINAL_PAYMENT', '$status', '$source', ${returnId ?: "NULL"}, now(), now(), 900, $failedOn)
            """.trimIndent(),
        )
    }

    @Test
    fun `the refunds of phase 2 go in as before - a cancellation's, staff's by hand, a change request's`() {
        insertRefund("CANCELLATION", null)
        insertRefund("MANUAL", null)
        insertRefund("CHANGE_REQUEST", null)
    }

    @Test
    fun `a return's refund needs its source, and a RETURN refund its return`() {
        val withoutSource = assertFailsWith<ExposedSQLException> { insertRefund("MANUAL", NO_SUCH_RETURN) }
        val withoutReturn = assertFailsWith<ExposedSQLException> { insertRefund("RETURN", null) }

        listOf(withoutSource, withoutReturn).forEach { assertEquals("refund_return_source_check", (it.cause as? PSQLException)?.serverErrorMessage?.constraint) }
    }

    @Test
    fun `one refund of a return that isn't FAILED at a time - after a FAILED one another goes in`() {
        insertRefund("RETURN", NO_SUCH_RETURN, status = "FAILED")
        insertRefund("RETURN", NO_SUCH_RETURN)

        val refused = assertFailsWith<ExposedSQLException> { insertRefund("RETURN", NO_SUCH_RETURN) }

        assertEquals("refund_one_live_per_return_idx", (refused.cause as? PSQLException)?.serverErrorMessage?.constraint)
    }

    private companion object {
        /** A return id no real return of another test takes (they come from the sequence): refund_tbl has no foreign key. */
        const val NO_SUCH_RETURN = 990_012L
    }
}
