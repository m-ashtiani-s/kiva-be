package com.kiva.infrastructure.common.http

import com.kiva.common.pagination.InvalidPagination
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.pagination.Sort
import com.kiva.common.railway.Result
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

/**
 * Reads `?page=2&size=20&sort=-price,name` from the request.
 *
 * - `page` defaults to 1, `size` to [Pageable.DEFAULT_SIZE].
 * - `sort` is a comma-separated list; a leading `-` means descending (`-price` = most expensive first).
 * - Only names in [allowedSorts] are accepted; anything else is a 400.
 *
 * ```kotlin
 * get("/products") {
 *     val pageable = call.pageable(allowedSorts = ProductSorts.names).unwrapOr { return@get call.respondFailure(it) }
 *     ...
 * }
 * ```
 */
fun ApplicationCall.pageable(allowedSorts: Set<String>): Result<Pageable, InvalidPagination> {
    val params = request.queryParameters
    return Pageable.of(
        page = params["page"].toIntParam(default = 1),
        size = params["size"].toIntParam(default = Pageable.DEFAULT_SIZE),
        sort = parseSort(params["sort"]),
        allowedSorts = allowedSorts,
    )
}

// Missing -> default. Not a number ("abc") -> 0, which Pageable.of rejects with the right failure.
private fun String?.toIntParam(default: Int): Int = if (this == null) default else toIntOrNull() ?: 0

private fun parseSort(raw: String?): List<Sort> =
    raw.orEmpty()
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { if (it.startsWith('-')) Sort(it.drop(1), Sort.Direction.DESC) else Sort(it, Sort.Direction.ASC) }

/** The JSON shape of every paginated list. */
@Serializable
data class PageHttpResponse<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Long,
)

fun <T, R> Page<T>.toHttpResponse(transform: (T) -> R): PageHttpResponse<R> =
    PageHttpResponse(items.map(transform), page, size, totalItems, totalPages)
