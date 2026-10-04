package com.kiva.common.pagination

/**
 * One slice of a longer list, plus what the client needs to draw page numbers.
 *
 * Asking for a page past the end is not an error: it simply has no [items].
 */
data class Page<out T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
) {
    val totalPages: Long get() = if (totalItems == 0L) 0 else (totalItems + size - 1) / size

    /** Converts every item, keeping the page information (e.g. domain object -> DTO). */
    inline fun <R> map(transform: (T) -> R): Page<R> = Page(items.map(transform), page, size, totalItems)
}
