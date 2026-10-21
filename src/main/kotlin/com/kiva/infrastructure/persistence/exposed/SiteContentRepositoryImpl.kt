package com.kiva.infrastructure.persistence.exposed

import com.kiva.application.repository.SiteContentQm
import com.kiva.application.repository.SiteContentRepository
import com.kiva.infrastructure.persistence.exposed.tbl.SiteContentTbl
import org.jetbrains.exposed.v1.jdbc.select

/** Runs inside the use case's transaction; it never opens one itself. */
class SiteContentRepositoryImpl : SiteContentRepository {

    /** The one row V31 wrote; its absence is a broken database, not an empty site. */
    override fun load(): SiteContentQm =
        SiteContentTbl.select(SiteContentTbl.document).singleOrNull()?.get(SiteContentTbl.document)?.toQm()
            ?: error("sitecontent_tbl has no row; V31 writes it")
}
