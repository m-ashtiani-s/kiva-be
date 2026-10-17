package com.kiva.infrastructure.common.persistence

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Query

/** What one named sort option means in the database, e.g. `"price_asc" to listOf(Products.price to SortOrder.ASC)`. */
typealias SortOrders = Map<String, List<Pair<Expression<*>, SortOrder>>>

/**
 * Runs this query for one page: counts all matching rows, then reads only the rows of [pageable].
 *
 * @param sortOrders the database meaning of each sort option the endpoint allows.
 *   Use its keys as `allowedSorts` in the route, so the two can't drift apart.
 *   An option may use several columns (e.g. "newest" = in-stock first, then by date).
 * @param tiebreaker a unique column (usually the id), always added as the last sort, in [tiebreakerOrder].
 *   Without it, rows with equal sort values (two products with the same price) can come back in a different
 *   order on each query, and the same product can then appear on page 1 and page 2 while another never shows.
 */
fun <T> Query.paginate(
    pageable: Pageable,
    sortOrders: SortOrders,
    tiebreaker: Column<*>,
    tiebreakerOrder: SortOrder = SortOrder.ASC,
    mapper: (ResultRow) -> T,
): Page<T> {
    val totalItems = copy().count()

    val requested = pageable.sort?.let { name ->
        sortOrders[name] ?: error("No database order for sort '$name'. Add it to sortOrders or remove it from allowedSorts.")
    }.orEmpty()
    val order = requested + (tiebreaker to tiebreakerOrder)

    val items = copy()
        .orderBy(*order.toTypedArray())
        .limit(pageable.size)
        .offset(pageable.offset)
        .map(mapper)

    return Page(items, pageable.page, pageable.size, totalItems)
}
