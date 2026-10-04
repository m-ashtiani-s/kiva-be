package com.kiva.infrastructure.common.persistence

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.pagination.Sort
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Query

/**
 * Runs this query for one page: counts all matching rows, then reads only the rows of [pageable].
 *
 * @param sortColumns which column each allowed sort name means (`"price" to Products.price`).
 *   Use the same map's keys as `allowedSorts` in the route, so the two can't drift apart.
 * @param tiebreaker a unique column (usually the id), always added as the last sort.
 *   Without it, rows with equal sort values (two products with the same price) can come back in a different
 *   order on each query, and the same product can then appear on page 1 and page 2 while another never shows.
 */
fun <T> Query.paginate(
    pageable: Pageable,
    sortColumns: Map<String, Expression<*>>,
    tiebreaker: Column<*>,
    mapper: (ResultRow) -> T,
): Page<T> {
    val totalItems = copy().count()

    val order = pageable.sort.map { sort ->
        val column = sortColumns[sort.field]
            ?: error("No column mapped for sort '${sort.field}'. Add it to sortColumns or remove it from allowedSorts.")
        column to sort.direction.toSortOrder()
    } + (tiebreaker to SortOrder.ASC)

    val items = copy()
        .orderBy(*order.toTypedArray())
        .limit(pageable.size)
        .offset(pageable.offset)
        .map(mapper)

    return Page(items, pageable.page, pageable.size, totalItems)
}

private fun Sort.Direction.toSortOrder(): SortOrder = when (this) {
    Sort.Direction.ASC -> SortOrder.ASC
    Sort.Direction.DESC -> SortOrder.DESC
}
