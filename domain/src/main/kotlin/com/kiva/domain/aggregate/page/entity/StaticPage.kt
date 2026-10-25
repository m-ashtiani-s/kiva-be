package com.kiva.domain.aggregate.page.entity

import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.page.model.PageDraft
import java.time.Instant

/**
 * A page of the site written in the panel (terms, privacy, returns, about; roadmap phase 5, step 5.1), shown at
 * `/pages/{slug}`. Kept as a row; written whole each time ([write], [rewrite]) and never deleted (the contract has no
 * delete). Its slug is its name for good: a page is never renamed.
 *
 * @property draft its title, sanitized HTML and search engine fields.
 * @property updatedBy the panel user who saved it last; [UserId.SYSTEM] for one written by the system.
 */
class StaticPage private constructor(
    val slug: Slug,
    val draft: PageDraft,
    val updatedOn: Instant,
    val updatedBy: UserId,
) {
    /** The page with the panel's new [draft]; null when nothing would change, so an unchanged save writes and logs nothing. */
    fun rewrite(draft: PageDraft, by: UserId, now: Instant): StaticPage? {
        if (draft == this.draft) return null
        return StaticPage(slug, draft, now, by)
    }

    override fun equals(other: Any?): Boolean = other is StaticPage && other.slug == slug

    override fun hashCode(): Int = slug.hashCode()

    override fun toString(): String = "StaticPage(slug=${slug.value}, updatedOn=$updatedOn)"

    companion object {
        /** A page nobody wrote before, written by [by] at [now]. */
        fun write(slug: Slug, draft: PageDraft, by: UserId, now: Instant): StaticPage = StaticPage(slug, draft, now, by)

        fun constructFromPersisted(slug: Slug, draft: PageDraft, updatedOn: Instant, updatedBy: UserId): StaticPage =
            StaticPage(slug, draft, updatedOn, updatedBy)
    }
}
