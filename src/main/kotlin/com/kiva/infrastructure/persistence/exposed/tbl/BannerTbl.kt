package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTheme
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Banners. Created by V36__marketing.sql (Exposed never creates tables); keep them in step. */
object BannerTbl : LongIdTable(name = "banner_tbl", columnName = "pId") {
    const val ID_SEQUENCE = "bannerid_seq"

    val bannerId = long("bannerId").uniqueIndex()
    val placement = enumerationByName<BannerPlacement>("placement", 16)
    val theme = enumerationByName<BannerTheme>("theme", 8)
    val tagLabel = varchar("tagLabel", 30).nullable()
    val tagIcon = varchar("tagIcon", 30).nullable()
    val title = varchar("title", 80)
    val text = varchar("text", 200).nullable()
    val ctaLabel = varchar("ctaLabel", 30).nullable()
    val ctaUrl = varchar("ctaUrl", 300).nullable()
    val imageMediaId = varchar("imageMediaId", 26)
    val overlayHighlight = varchar("overlayHighlight", 30).nullable()
    val overlayTitle = varchar("overlayTitle", 30).nullable()
    val overlaySubtitle = varchar("overlaySubtitle", 30).nullable()
    val overlayChannel = enumerationByName<SocialChannel>("overlayChannel", 10).nullable()
    val sortOrder = integer("sortOrder")
    val startsAt = timestampWithTimeZone("startsAt").nullable()
    val endsAt = timestampWithTimeZone("endsAt").nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
}
