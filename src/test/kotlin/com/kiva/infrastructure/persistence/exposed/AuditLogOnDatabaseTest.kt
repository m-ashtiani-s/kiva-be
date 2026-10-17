package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.usecase.audit.usecase.command.DeleteOldAuditEntriesUc
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The change log's table (V21__audit_log.sql) and its nightly retention: the database refuses any change to a row and
 * the removal of a row younger than 90 days, whatever wrote the SQL; the job removes exactly the rows past that.
 *
 * The trigger reads the database's own clock, so the rows here are dated from the real now, and so is the job's clock.
 */
class AuditLogOnDatabaseTest {

    private val db = TestDatabase.database

    /** Rows of this class carry this actor, so a count never sees another test's rows. */
    private val actor = 900_000_000L + (System.nanoTime() % 1_000_000)

    @AfterEach
    fun deleteWhatWasStored() {
        // Rows younger than 90 days can't be removed (that is the point); the old ones the tests leave are removed here.
        transaction(db) { exec("DELETE FROM auditlog_tbl WHERE \"actorId\" = $actor AND \"at\" < now() - INTERVAL '90 days'") }
    }

    private fun JdbcTransaction.row(daysAgo: Int): Long =
        exec(
            "INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\") " +
                "VALUES (now() - INTERVAL '$daysAgo days', $actor, 'PRODUCT_UPDATED', 'PRODUCT', '12') RETURNING \"pId\"",
            explicitStatementType = StatementType.SELECT,
        ) {
            it.next()
            it.getLong(1)
        } ?: error("no row id")

    private fun JdbcTransaction.rows(daysAgo: Int, count: Int) =
        exec(
            "INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\") " +
                "SELECT now() - INTERVAL '$daysAgo days', $actor, 'PRODUCT_UPDATED', 'PRODUCT', g::text FROM generate_series(1, $count) g",
        )

    private fun countOf(daysAgo: Int? = null): Long = transaction(db) {
        val age = daysAgo?.let { " AND \"at\" < now() - INTERVAL '${it - 1} days' AND \"at\" >= now() - INTERVAL '${it + 1} days'" }.orEmpty()
        exec("SELECT count(*) FROM auditlog_tbl WHERE \"actorId\" = $actor$age") {
            it.next()
            it.getLong(1)
        } ?: 0
    }

    private fun refused(write: JdbcTransaction.() -> Unit) = transaction(db) {
        assertFailsWith<ExposedSQLException> { write() }
        rollback()
    }

    @Test
    fun `a row is never changed - not even an old one`() {
        val fresh = transaction(db) { row(daysAgo = 1) }
        val old = transaction(db) { row(daysAgo = 91) }

        refused { exec("UPDATE auditlog_tbl SET action = 'PRODUCT_CREATED' WHERE \"pId\" = $fresh") }
        refused { exec("UPDATE auditlog_tbl SET action = 'PRODUCT_CREATED' WHERE \"pId\" = $old") }
    }

    @Test
    fun `a row of 89 days is never removed - one of 91 days may be`() {
        val young = transaction(db) { row(daysAgo = 89) }
        val old = transaction(db) { row(daysAgo = 91) }

        refused { exec("DELETE FROM auditlog_tbl WHERE \"pId\" = $young") }
        transaction(db) { exec("DELETE FROM auditlog_tbl WHERE \"pId\" = $old") }
        assertEquals(1, countOf())
    }

    @Test
    fun `an action outside UPPER_SNAKE_CASE or an entity type outside the contract is refused`() {
        refused { exec("INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\") VALUES (now(), $actor, 'updated', 'PRODUCT', '1')") }
        refused { exec("INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\") VALUES (now(), $actor, 'X', 'BAG', '1')") }
        refused { exec("INSERT INTO auditlog_tbl (\"at\", \"actorId\", action, \"entityType\", \"entityId\", changes) VALUES (now(), $actor, 'X', 'PRODUCT', '1', '{}')") }
    }

    @Test
    fun `the job removes the rows of 91 days and keeps those of 89 - and a second run removes nothing`() = runBlocking<Unit> {
        transaction(db) {
            rows(daysAgo = 91, count = 3)
            rows(daysAgo = 89, count = 2)
        }
        val job = DeleteOldAuditEntriesUc(ExposedTransactionManager(db), AuditTrailImpl(), MutableClock(Instant.now()))

        job.execute(DeleteOldAuditEntriesUc.Cmd)

        assertEquals(0, countOf(daysAgo = 91))
        assertEquals(2, countOf(daysAgo = 89))
        assertEquals(0, job.execute(DeleteOldAuditEntriesUc.Cmd).getOrNull())
    }

    @Test
    fun `12000 old rows go in three batches`() = runBlocking<Unit> {
        transaction(db) { rows(daysAgo = 100, count = 12_000) }
        val batches = mutableListOf<Int>()
        val counting = object : AuditTrail {
            private val real = AuditTrailImpl()
            override fun record(entry: AuditEntry) = real.record(entry)
            override fun deleteRecordedBefore(before: Instant, limit: Int): Int = real.deleteRecordedBefore(before, limit).also { batches += it }
        }

        val removed = DeleteOldAuditEntriesUc(ExposedTransactionManager(db), counting, MutableClock(Instant.now())).execute(DeleteOldAuditEntriesUc.Cmd)

        assertEquals(12_000, removed.getOrNull())
        assertEquals(listOf(5000, 5000, 2000), batches)
        assertEquals(0, countOf())
    }
}
