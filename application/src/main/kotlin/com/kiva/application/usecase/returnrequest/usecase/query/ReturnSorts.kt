package com.kiva.application.usecase.returnrequest.usecase.query

/**
 * The orders the panel's return queue offers (contract `sort` of `GET /admin/returns`; roadmap section 12.2, decision
 * 19): newest asked first, or oldest first, so the returns that never arrived are seen (nothing closes them by itself).
 * Ties go to the return's id, the same way.
 */
object ReturnSorts {
    const val NEWEST = "newest"
    const val OLDEST = "oldest"

    const val DEFAULT = NEWEST

    val names: Set<String> = setOf(NEWEST, OLDEST)
}
