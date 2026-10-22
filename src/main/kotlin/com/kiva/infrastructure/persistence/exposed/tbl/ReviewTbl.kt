package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.review.model.valueobject.ReviewStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/** Reviews, one row each. Created by V33__reviews.sql (Exposed never creates tables); keep both in step. */
object ReviewTbl : LongIdTable(name = "review_tbl", columnName = "pId") {
    val reviewId = long("reviewId").uniqueIndex()
    val productId = long("productId")
    val userId = long("userId")
    val rating = short("rating")
    val authorName = varchar("authorName", 40).nullable()
    val contact = varchar("contact", 100).nullable()
    val text = varchar("text", 1000)
    val status = enumerationByName<ReviewStatus>("status", 8)
    val rejectionReason = varchar("rejectionReason", 300).nullable()
    val orderId = long("orderId").nullable()
    val orderCode = varchar("orderCode", 16).nullable()
    val lineId = integer("lineId").nullable()
    val colorKey = varchar("colorKey", 40).nullable()
    val colorName = varchar("colorName", 40).nullable()
    val colorHex = varchar("colorHex", 7).nullable()
    val mediaIds = jsonb<List<String>>("mediaIds", Json)
    val helpfulCount = integer("helpfulCount")
    val replyText = varchar("replyText", 1000).nullable()
    val repliedOn = timestampWithTimeZone("repliedOn").nullable()
    val repliedBy = long("repliedBy").nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
    val moderatedOn = timestampWithTimeZone("moderatedOn").nullable()
    val moderatedBy = long("moderatedBy").nullable()
    val version = long("version")

    const val ID_SEQUENCE = "reviewid_seq"

    /** One live review per user and product (decision 2): the wall behind ReviewDomainService's check. */
    const val ONE_LIVE_KEY = "review_one_live_idx"
}

/** Who found which review helpful. Created by V33__reviews.sql. */
object ReviewVoteTbl : Table("reviewvote_tbl") {
    val reviewId = long("reviewId")
    val userId = long("userId")
    val createdOn = timestampWithTimeZone("createdOn")

    override val primaryKey = PrimaryKey(reviewId, userId)
}

/** Each product's rating over its approved reviews. Created by V33__reviews.sql. */
object ProductRatingTbl : Table("productrating_tbl") {
    val productId = long("productId")
    val count = integer("count")
    val sum = integer("sum")
    val stars1 = integer("stars1")
    val stars2 = integer("stars2")
    val stars3 = integer("stars3")
    val stars4 = integer("stars4")
    val stars5 = integer("stars5")
    val verifiedCount = integer("verifiedCount")
    val updatedOn = timestampWithTimeZone("updatedOn")

    override val primaryKey = PrimaryKey(productId)

    val starColumns get() = listOf(stars1, stars2, stars3, stars4, stars5)
}
