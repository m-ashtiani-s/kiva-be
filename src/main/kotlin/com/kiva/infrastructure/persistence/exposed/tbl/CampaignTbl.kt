package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Campaigns. Created by V36__campaigns.sql (Exposed never creates tables); keep them in step. */
object CampaignTbl : LongIdTable(name = "campaign_tbl", columnName = "pId") {
    const val ID_SEQUENCE = "campaignid_seq"
    const val SLUG_KEY = "campaign_tbl_slug_key"
    const val NO_OVERLAP = "campaign_no_overlap"

    val campaignId = long("campaignId").uniqueIndex()
    val slug = varchar("slug", 60)
    val title = varchar("title", 80)
    val subtitle = varchar("subtitle", 120).nullable()
    val icon = varchar("icon", 30).nullable()
    val startsAt = timestampWithTimeZone("startsAt")
    val endsAt = timestampWithTimeZone("endsAt")
    val productIds = array<Long>("productIds")
    val isActive = bool("isActive")
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
}
