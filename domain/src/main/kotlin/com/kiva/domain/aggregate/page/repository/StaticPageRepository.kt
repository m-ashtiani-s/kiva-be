package com.kiva.domain.aggregate.page.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.page.entity.StaticPage

/** The site's static pages. Runs inside the use case's transaction. */
interface StaticPageRepository {

    fun find(slug: Slug): StaticPage?

    /**
     * Holds the page's name until the transaction ends (an advisory lock keyed by [slug]), so two saves of one page, even
     * of a page not written yet, run one after the other: the second reads what the first wrote.
     */
    fun lock(slug: Slug)

    /** Writes the page: a new row for a new slug, or over the old one. */
    fun save(page: StaticPage)

    /** Every page, by slug. */
    fun list(): List<StaticPage>
}
