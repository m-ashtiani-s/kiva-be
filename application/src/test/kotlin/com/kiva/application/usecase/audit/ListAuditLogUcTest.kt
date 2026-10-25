package com.kiva.application.usecase.audit

import com.kiva.application.repository.AuditLogEntryQm
import com.kiva.application.repository.AuditLogFilter
import com.kiva.application.repository.AuditLogQmRepository
import com.kiva.application.usecase.audit.usecase.query.ListAuditLogUc
import com.kiva.application.util.MockTransactionManager
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** What the change log's query makes of its filters before the database, and the filters it refuses. */
class ListAuditLogUcTest {

    private val transactions = MockTransactionManager()
    private val pageable = Pageable.makeNew(page = 1, size = 20).getOrNull() ?: error("no pageable")

    /** Remembers the filter it was asked for; finds nothing. */
    private class RecordingLog : AuditLogQmRepository {
        var asked: AuditLogFilter? = null

        override fun find(filter: AuditLogFilter, pageable: Pageable): Page<AuditLogEntryQm> {
            asked = filter
            return Page(emptyList(), pageable.page, pageable.size, 0)
        }
    }

    private val log = RecordingLog()
    private val useCase = ListAuditLogUc(transactions, log)

    @Test
    fun `no filter at all reads everything`() = runTest {
        useCase.execute(ListAuditLogUc.Qry(pageable))

        assertEquals(AuditLogFilter(), log.asked)
    }

    @Test
    fun `from and to are whole Tehran days - from 00 00 of the first to 00 00 after the last`() = runTest {
        useCase.execute(ListAuditLogUc.Qry(pageable, from = "2026-10-15", to = "۲۰۲۶-۱۰-۱۶"))

        // Tehran is UTC+03:30 all year.
        assertEquals(Instant.parse("2026-10-14T20:30:00Z"), log.asked?.from)
        assertEquals(Instant.parse("2026-10-16T20:30:00Z"), log.asked?.until)
    }

    @Test
    fun `entityType, entityId and actorId are read as given, the id without its spaces`() = runTest {
        useCase.execute(ListAuditLogUc.Qry(pageable, entityType = "ORDER", entityId = " KV-218340 ", actorId = "۷"))

        assertEquals(AuditLogFilter(entityType = AuditEntityType.ORDER, entityId = "KV-218340", actorId = 7), log.asked)
    }

    @Test
    fun `an entityId alone is allowed`() = runTest {
        useCase.execute(ListAuditLogUc.Qry(pageable, entityId = "lilac"))

        assertEquals(AuditLogFilter(entityId = "lilac"), log.asked)
    }

    @Test
    fun `every bad filter is reported at once, under its own name, and no transaction is opened`() = runTest {
        val failure = useCase.execute(
            ListAuditLogUc.Qry(pageable, entityType = "BAG", entityId = "x".repeat(65), actorId = "0", from = "25/07/1405", to = "tomorrow"),
        ).failureOrNull()

        assertEquals(
            listOf(
                "entityType" to "AUDIT_ENTITY_TYPE_INVALID",
                "entityId" to "ENTITY_ID_TOO_LONG",
                "actorId" to "NUMBER_INVALID",
                "from" to "DATE_INVALID",
                "to" to "DATE_INVALID",
            ),
            failure?.causes?.map { it.field to it.code },
        )
        assertEquals(0, transactions.transactionsOpened)
    }

    @Test
    fun `from after to is 400 on to with DATE_RANGE_INVALID - the same day is fine`() = runTest {
        val failure = useCase.execute(ListAuditLogUc.Qry(pageable, from = "2026-10-16", to = "2026-10-15")).failureOrNull()

        assertEquals(listOf("to" to "DATE_RANGE_INVALID"), failure?.causes?.map { it.field to it.code })
        assertEquals(null, useCase.execute(ListAuditLogUc.Qry(pageable, from = "2026-10-16", to = "2026-10-16")).failureOrNull())
    }
}
