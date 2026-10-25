package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.page.entity.StaticPage
import com.kiva.domain.aggregate.page.model.PageDraft
import com.kiva.domain.aggregate.page.repository.StaticPageRepository
import com.kiva.infrastructure.persistence.exposed.tbl.StaticPageTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class StaticPageRepositoryImpl : StaticPageRepository {

    override fun find(slug: Slug): StaticPage? =
        StaticPageTbl.selectAll().where { StaticPageTbl.slug eq slug.value }.singleOrNull()?.toPage()

    /**
     * A transaction-level advisory lock keyed by the page's slug: it works for a page that has no row yet, and the commit
     * or rollback releases it. Two slugs may share a key (a 64-bit hash); that only makes them wait for each other.
     */
    override fun lock(slug: Slug) {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", listOf(TextColumnType() to "page:${slug.value}")) { }
    }

    /** Under [lock], so the row read a moment ago is still the one there: an update when there is one, an insert when not. */
    override fun save(page: StaticPage) {
        val updated = StaticPageTbl.update({ StaticPageTbl.slug eq page.slug.value }) { it.write(page) }
        if (updated == 0) {
            StaticPageTbl.insert {
                it[slug] = page.slug.value
                it.write(page)
            }
        }
    }

    override fun list(): List<StaticPage> = StaticPageTbl.selectAll().orderBy(StaticPageTbl.slug to SortOrder.ASC).map { it.toPage() }

    private fun UpdateBuilder<*>.write(page: StaticPage) {
        this[StaticPageTbl.title] = page.draft.title
        this[StaticPageTbl.content] = page.draft.content
        this[StaticPageTbl.seoTitle] = page.draft.seo.title
        this[StaticPageTbl.seoDescription] = page.draft.seo.description
        this[StaticPageTbl.seoNoIndex] = page.draft.seo.noIndex
        this[StaticPageTbl.updatedOn] = page.updatedOn.atOffset(ZoneOffset.UTC)
        this[StaticPageTbl.updatedBy] = page.updatedBy.value
    }

    private fun ResultRow.toPage() = StaticPage.constructFromPersisted(
        slug = Slug.constructFromPersisted(this[StaticPageTbl.slug]),
        draft = PageDraft.constructFromPersisted(
            this[StaticPageTbl.title],
            this[StaticPageTbl.content],
            SeoMeta.constructFromPersisted(this[StaticPageTbl.seoTitle], this[StaticPageTbl.seoDescription], this[StaticPageTbl.seoNoIndex]),
        ),
        updatedOn = this[StaticPageTbl.updatedOn].toInstant(),
        updatedBy = UserId.constructFromPersisted(this[StaticPageTbl.updatedBy]),
    )
}
