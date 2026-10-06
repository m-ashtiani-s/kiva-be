package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The events of every event-sourced aggregate, one row each, only ever added. Created by V3__event_store.sql
 * (Exposed never creates tables); keep both in step.
 *
 * [payload] holds the JSON that DomainEventSerializer writes and reads; it is already text, so the column passes it
 * through as it is.
 */
object EventStoreTbl : LongIdTable(name = "eventstore_tbl", columnName = "pId") {
    val eventId = javaUUID("eventId").uniqueIndex()
    val aggregateName = varchar("aggregateName", 50)
    val aggregateId = varchar("aggregateId", 255)
    val sequence = long("sequence")
    val eventType = varchar("eventType", 100)
    val payloadVersion = integer("payloadVersion")
    val payload = jsonb<String>("payload", { it }, { it })
    val occurredOn = timestampWithTimeZone("occurredOn")
    val occurredByUserId = long("occurredByUserId")

    /** The unique key on (aggregateName, aggregateId, sequence); the database names it when it refuses a save. */
    const val STREAM_SEQUENCE_KEY = "eventstore_tbl_stream_sequence_key"

    init {
        uniqueIndex(STREAM_SEQUENCE_KEY, aggregateName, aggregateId, sequence)
    }
}
