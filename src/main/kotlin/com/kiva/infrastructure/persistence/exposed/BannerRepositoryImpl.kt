package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.OpenWindow
import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.banner.entity.Banner
import com.kiva.domain.aggregate.banner.model.valueobject.BannerCta
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.model.valueobject.BannerOverlay
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTag
import com.kiva.domain.aggregate.banner.repository.BannerRepository
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.infrastructure.persistence.exposed.tbl.BannerTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class BannerRepositoryImpl : BannerRepository {

    override fun nextId(): BannerId =
        TransactionManager.current().exec("SELECT nextval('${BannerTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            BannerId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${BannerTbl.ID_SEQUENCE}') returned nothing")

    override fun find(id: BannerId): Banner? = BannerTbl.selectAll().where { BannerTbl.bannerId eq id.value }.singleOrNull()?.toBanner()

    override fun findAll(): List<Banner> =
        BannerTbl.selectAll().orderBy(BannerTbl.placement to SortOrder.ASC, BannerTbl.sortOrder to SortOrder.ASC, BannerTbl.bannerId to SortOrder.ASC)
            .map { it.toBanner() }

    // The window's rule, as OpenWindow.contains: started (or no start) and not yet ended (or no end).
    override fun findVisible(placement: BannerPlacement, now: Instant, limit: Int): List<Banner> {
        val at = now.utc()
        return BannerTbl.selectAll().where {
            (BannerTbl.placement eq placement) and (BannerTbl.startsAt.isNull() or (BannerTbl.startsAt lessEq at)) and
                (BannerTbl.endsAt.isNull() or (BannerTbl.endsAt greater at))
        }.orderBy(BannerTbl.sortOrder to SortOrder.ASC, BannerTbl.bannerId to SortOrder.ASC).limit(limit).map { it.toBanner() }
    }

    override fun add(banner: Banner) {
        BannerTbl.insert {
            it[bannerId] = banner.id.value
            it[createdOn] = banner.createdOn.utc()
            it.fill(banner)
        }
    }

    override fun save(banner: Banner) {
        BannerTbl.update({ BannerTbl.bannerId eq banner.id.value }) { it.fill(banner) }
    }

    override fun delete(id: BannerId): Boolean = BannerTbl.deleteWhere { BannerTbl.bannerId eq id.value } > 0

    private fun UpdateBuilder<*>.fill(banner: Banner) {
        this[BannerTbl.placement] = banner.placement
        this[BannerTbl.theme] = banner.theme
        this[BannerTbl.tagLabel] = banner.tag?.label
        this[BannerTbl.tagIcon] = banner.tag?.icon?.value
        this[BannerTbl.title] = banner.title
        this[BannerTbl.text] = banner.text
        this[BannerTbl.ctaLabel] = banner.cta?.label
        this[BannerTbl.ctaUrl] = banner.cta?.url?.value
        this[BannerTbl.imageMediaId] = banner.imageMediaId.value
        this[BannerTbl.overlayHighlight] = banner.overlay?.highlight
        this[BannerTbl.overlayTitle] = banner.overlay?.title
        this[BannerTbl.overlaySubtitle] = banner.overlay?.subtitle
        this[BannerTbl.overlayChannel] = banner.overlay?.channel
        this[BannerTbl.sortOrder] = banner.sortOrder
        this[BannerTbl.startsAt] = banner.window.startsAt?.utc()
        this[BannerTbl.endsAt] = banner.window.endsAt?.utc()
        this[BannerTbl.updatedOn] = banner.updatedOn.utc()
    }

    private fun ResultRow.toBanner(): Banner {
        val highlight = this[BannerTbl.overlayHighlight]
        val overlayTitle = this[BannerTbl.overlayTitle]
        val overlaySubtitle = this[BannerTbl.overlaySubtitle]
        val channel = this[BannerTbl.overlayChannel]
        val hasOverlay = highlight != null || overlayTitle != null || overlaySubtitle != null || channel != null
        val ctaLabel = this[BannerTbl.ctaLabel]
        val ctaUrl = this[BannerTbl.ctaUrl]
        return Banner.constructFromPersisted(
            id = BannerId.constructFromPersisted(this[BannerTbl.bannerId]),
            placement = this[BannerTbl.placement],
            theme = this[BannerTbl.theme],
            tag = this[BannerTbl.tagLabel]?.let { BannerTag(it, this[BannerTbl.tagIcon]?.let { icon -> IconName.constructFromPersisted(icon) }) },
            title = this[BannerTbl.title],
            text = this[BannerTbl.text],
            // The table's CHECK keeps the two together.
            cta = if (ctaLabel != null && ctaUrl != null) BannerCta(ctaLabel, SiteLink.constructFromPersisted(ctaUrl)) else null,
            imageMediaId = MediaId.constructFromPersisted(this[BannerTbl.imageMediaId]),
            overlay = if (hasOverlay) BannerOverlay(highlight, overlayTitle, overlaySubtitle, channel) else null,
            sortOrder = this[BannerTbl.sortOrder],
            window = OpenWindow(this[BannerTbl.startsAt]?.toInstant(), this[BannerTbl.endsAt]?.toInstant()),
            createdOn = this[BannerTbl.createdOn].toInstant(),
            updatedOn = this[BannerTbl.updatedOn].toInstant(),
        )
    }

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)
}
