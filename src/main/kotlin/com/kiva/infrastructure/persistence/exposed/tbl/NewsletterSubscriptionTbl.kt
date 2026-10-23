package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Newsletter subscribers. Created by V38__contact_newsletter.sql (Exposed never creates tables); keep them in step. */
object NewsletterSubscriptionTbl : LongIdTable(name = "newslettersubscription_tbl", columnName = "pId") {
    val email = varchar("email", 254).uniqueIndex()
    val status = enumerationByName<SubscriptionStatus>("status", 12)
    val sourceName = varchar("source", 30).nullable()
    val token = varchar("token", 35).uniqueIndex()
    val subscribedAt = timestampWithTimeZone("subscribedAt")
    val unsubscribedAt = timestampWithTimeZone("unsubscribedAt").nullable()
}
