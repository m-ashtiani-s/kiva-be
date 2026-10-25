package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import com.kiva.domain.aggregate.announcement.model.valueobject.AnnouncementId
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.infrastructure.persistence.exposed.tbl.AnnouncementTbl
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class AnnouncementBarRepositoryImpl : AnnouncementBarRepository {

    override fun lock() {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(TextColumnType() to BAR_LOCK)) { }
    }

    override fun load(): AnnouncementBar = AnnouncementBar(
        AnnouncementTbl.selectAll().orderBy(AnnouncementTbl.sortOrder to SortOrder.ASC).map { row ->
            AnnouncementBar.Announcement(
                id = AnnouncementId.constructFromPersisted(row[AnnouncementTbl.announcementId]),
                icon = row[AnnouncementTbl.icon]?.let(IconName::constructFromPersisted),
                text = row[AnnouncementTbl.text],
                url = row[AnnouncementTbl.url]?.let(SiteLink::constructFromPersisted),
                startsAt = row[AnnouncementTbl.startsAt]?.toInstant(),
                endsAt = row[AnnouncementTbl.endsAt]?.toInstant(),
            )
        },
    )

    override fun nextId(): AnnouncementId =
        TransactionManager.current().exec("SELECT nextval('${AnnouncementTbl.ID_SEQUENCE}')") { rows ->
            rows.next()
            AnnouncementId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${AnnouncementTbl.ID_SEQUENCE}') returned nothing")

    /** Under [lock]: the old list goes and [bar] is written in its order. */
    override fun save(bar: AnnouncementBar) {
        AnnouncementTbl.deleteAll()
        AnnouncementTbl.batchInsert(bar.items.withIndex()) { (i, item) ->
            this[AnnouncementTbl.announcementId] = item.id.value
            this[AnnouncementTbl.icon] = item.icon?.value
            this[AnnouncementTbl.text] = item.text
            this[AnnouncementTbl.url] = item.url?.value
            this[AnnouncementTbl.sortOrder] = i
            this[AnnouncementTbl.startsAt] = item.startsAt?.atOffset(ZoneOffset.UTC)
            this[AnnouncementTbl.endsAt] = item.endsAt?.atOffset(ZoneOffset.UTC)
        }
    }

    private companion object {
        const val BAR_LOCK = "announcements"
    }
}
