package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.projector.OrderQmProjector
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * V26's backfill of the rows already there: the same UPDATE statements as the migration, read from its file, run on a row
 * written in the shape before it (a timeline without kinds, no searchText), and only on that row.
 */
class OrderAdminReadModelMigrationTest {

    private val db = TestDatabase.database
    private val code = "KV-777777"

    @AfterEach
    fun removeRow() {
        transaction(db) { exec("""DELETE FROM orderqm_tbl WHERE code = '$code'""") }
    }

    /** V26's UPDATEs of orderqm_tbl, each limited to the test's row. */
    private val backfills: List<String> by lazy {
        val sql = File("src/main/resources/db/migration/V26__order_admin_read_model.sql").readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        sql.split(";").map { it.trim() }.filter { it.startsWith("UPDATE orderqm_tbl") }.map { "$it WHERE code = '$code'" }
            .also { check(it.size == 2) { "Expected V26's two backfills of orderqm_tbl, found ${it.size}" } }
    }

    private fun insertBefore(recipientName: String, recipientPhone: String, city: String, timeline: String) = transaction(db) {
        exec(
            """
            INSERT INTO orderqm_tbl ("orderId", code, "userId", status, "placedOn", "paidOn", "holdUntil", payable, "itemsCount",
                "shippingMethod", "messengerChannel", "reserveRequested", lines, address, messenger, totals, timeline, "updatedOn")
            VALUES (987654323, '$code', 7, 'PROCESSING', now(), now(), now() + interval '15 minutes', 1450000, 1, 'POST', 'TELEGRAM', false, '[{}]',
                '{"recipientName": "$recipientName", "recipientPhone": "$recipientPhone", "cityName": "$city"}', '{}', '{}', '$timeline', now())
            """.trimIndent(),
        )
        backfills.forEach { exec(it) }
    }

    @Test
    fun `a held order let go of by its reservation reads PLACED, a failed attempt, PAID, RELEASED_FROM_RESERVATION`() {
        insertBefore(
            "سارا محمدی", "09121234567", "تهران",
            """[{"status":"PENDING_PAYMENT","at":"2026-10-12T08:00:00Z","failureReason":null},
                {"status":"PAYMENT_FAILED","at":"2026-10-12T08:01:00Z","failureReason":"USER_CANCELLED"},
                {"status":"RESERVED","at":"2026-10-12T08:02:00Z","failureReason":null},
                {"status":"PROCESSING","at":"2026-10-16T08:02:00Z","failureReason":null}]""",
        )

        val timeline = transaction(db) { OrderQmTbl.select(OrderQmTbl.timeline).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.timeline] }

        assertEquals(listOf("PLACED", "PAYMENT_FAILED", "PAID", "RELEASED_FROM_RESERVATION"), timeline.map { it.kind })
        assertEquals("USER_CANCELLED", timeline[1].failureReason)
    }

    @Test
    fun `an order paid at once reads PLACED then PAID, and an expired one PLACED then EXPIRED`() {
        insertBefore(
            "سارا محمدی", "09121234567", "تهران",
            """[{"status":"PENDING_PAYMENT","at":"2026-10-12T08:00:00Z","failureReason":null},{"status":"PROCESSING","at":"2026-10-12T08:00:00Z","failureReason":null}]""",
        )
        val paid = transaction(db) { OrderQmTbl.select(OrderQmTbl.timeline).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.timeline] }.map { it.kind }
        removeRow()
        insertBefore(
            "سارا محمدی", "09121234567", "تهران",
            """[{"status":"PENDING_PAYMENT","at":"2026-10-12T08:00:00Z","failureReason":null},{"status":"EXPIRED","at":"2026-10-12T08:15:00Z","failureReason":null}]""",
        )
        val expired = transaction(db) { OrderQmTbl.select(OrderQmTbl.timeline).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.timeline] }.map { it.kind }

        assertEquals(listOf("PLACED", "PAID"), paid)
        assertEquals(listOf("PLACED", "EXPIRED"), expired)
    }

    @Test
    fun `the SQL's searchText is the projector's for the same address - Arabic letters, Persian digits, half-spaces and spaces`() {
        val name = "علي  ك\u200C\u200Cريمي\u200C "
        val phone = "۰۹۱۲۱۲۳۴۵۶۷"
        val city = "مشهد"
        insertBefore(name, phone, city, """[{"status":"PENDING_PAYMENT","at":"2026-10-12T08:00:00Z","failureReason":null}]""")

        val stored = transaction(db) { OrderQmTbl.select(OrderQmTbl.searchText).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.searchText] }

        assertEquals("KV-777777 علی ک\u200Cریمی 09121234567 مشهد", stored)
        assertEquals(stored, OrderQmProjector.searchTextOf(code, name, phone, city))
    }
}
