package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.product.entity.event.ProductDomainEvent
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.event.ProductEventSamples
import com.kiva.infrastructure.persistence.event.serializer.DomainEventSerializer
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V3__event_store.sql: events go through the real table (JSONB included) unchanged, and the database itself refuses
 * what must never be stored. Every write here is rolled back.
 */
class EventStoreMigrationTest {

    private val db = TestDatabase.database

    private fun JdbcTransaction.append(
        event: ProductDomainEvent,
        eventId: UUID = UUID.fromString(event.id.value),
        aggregateName: String = event.aggregateName.value,
        sequence: Long = event.sequence.value,
        payload: String = DomainEventSerializer.serialize(event),
    ) {
        EventStoreTbl.insert {
            it[EventStoreTbl.eventId] = eventId
            it[EventStoreTbl.aggregateName] = aggregateName
            it[aggregateId] = event.aggregateId.value
            it[EventStoreTbl.sequence] = sequence
            it[eventType] = event.type.value
            it[payloadVersion] = event.version.value
            it[EventStoreTbl.payload] = payload
            it[occurredOn] = event.occurredOn.atOffset(ZoneOffset.UTC)
            it[occurredByUserId] = event.occurredByUserId.value
        }
    }

    /** The write must fail, and the database names the rule that refused it. */
    private fun assertRefusedBy(constraint: String, write: JdbcTransaction.() -> Unit) = transaction(db) {
        val error = assertFailsWith<ExposedSQLException> { write() }
        assertEquals(constraint, (error.cause as PSQLException).serverErrorMessage?.constraint)
        rollback()
    }

    private val created = ProductEventSamples.productCreated
    private val variantAdded = ProductEventSamples.variantAdded

    @Test
    fun `every kind of event goes in and comes back the same, through the JSONB column`() {
        val readBack = transaction(db) {
            ProductEventSamples.onePerKind.forEach { append(it) }
            val events = EventStoreTbl.selectAll()
                .where { EventStoreTbl.aggregateId eq created.aggregateId.value }
                .orderBy(EventStoreTbl.sequence to SortOrder.ASC)
                .map { DomainEventSerializer.deserializeProduct(it[EventStoreTbl.payload], it[EventStoreTbl.payloadVersion], it[EventStoreTbl.eventType]) }
            rollback()
            events
        }

        assertEquals(ProductEventSamples.onePerKind, readBack)
    }

    @Test
    fun `a second event with the same number in one stream is refused`() = assertRefusedBy(EventStoreTbl.STREAM_SEQUENCE_KEY) {
        append(created)
        append(variantAdded, sequence = 1)
    }

    @Test
    fun `the same number in another kind of stream is fine`() {
        transaction(db) {
            append(created)
            append(variantAdded, aggregateName = "INVENTORY", sequence = 1)
            rollback()
        }
    }

    @Test
    fun `an event id is stored only once`() = assertRefusedBy("eventstore_tbl_eventId_key") {
        append(created)
        append(variantAdded, eventId = UUID.fromString(created.id.value))
    }

    @Test
    fun `a stream starts at 1`() = assertRefusedBy("eventstore_tbl_sequence_check") {
        append(created, sequence = 0)
    }

    @Test
    fun `a payload must be a JSON object`() = assertRefusedBy("eventstore_tbl_payload_check") {
        append(created, payload = "[1, 2]")
    }

    @Test
    fun `an aggregate name must be UPPER_SNAKE`() = assertRefusedBy("eventstore_tbl_aggregateName_check") {
        append(created, aggregateName = "Product")
    }
}
