package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Testimonials. Created by V36__marketing.sql (Exposed never creates tables); keep them in step. */
object TestimonialTbl : LongIdTable(name = "testimonial_tbl", columnName = "pId") {
    const val ID_SEQUENCE = "testimonialid_seq"

    val testimonialId = long("testimonialId").uniqueIndex()
    val customerName = varchar("customerName", 40)
    val city = varchar("city", 40).nullable()
    val sourceChannel = enumerationByName<SocialChannel>("source", 10)
    val message = varchar("message", 500)
    val messageTime = varchar("messageTime", 5).nullable()
    val receivedAt = timestampWithTimeZone("receivedAt").nullable()
    val rating = short("rating")
    val productId = long("productId")
    val colorKey = varchar("colorKey", 31).nullable()
    val screenshotMediaId = varchar("screenshotMediaId", 26).nullable()
    val isPublished = bool("isPublished")
    val sortOrder = integer("sortOrder")
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
}
