package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.ReplyChannel
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Contact messages. Created by V38__contact_newsletter.sql (Exposed never creates tables); keep them in step. */
object ContactMessageTbl : LongIdTable(name = "contactmessage_tbl", columnName = "pId") {
    val ticketCode = varchar("ticketCode", 9).uniqueIndex()
    val topic = enumerationByName<ContactTopic>("topic", 16)
    val fullName = varchar("fullName", 80)
    val phone = varchar("phone", 11)
    val email = varchar("email", 254).nullable()
    val orderCode = varchar("orderCode", 16).nullable()
    val message = varchar("message", 600)
    val status = enumerationByName<ContactStatus>("status", 12)
    val reply = varchar("reply", 1000).nullable()
    val replyChannel = enumerationByName<ReplyChannel>("replyChannel", 5).nullable()
    val repliedAt = timestampWithTimeZone("repliedAt").nullable()
    val repliedBy = long("repliedBy").nullable()
    val internalNote = varchar("internalNote", 1000).nullable()
    val userId = long("userId").nullable()
    val createdAt = timestampWithTimeZone("createdAt")
    val updatedAt = timestampWithTimeZone("updatedAt")
}
