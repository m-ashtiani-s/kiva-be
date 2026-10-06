package com.kiva.infrastructure.common.http

import com.kiva.common.pagination.InvalidPagination
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.Serializable

/**
 * Reads `?page=2&size=24&sort=price_asc` from the request.
 *
 * Each endpoint passes its own rules (from the API contract):
 * ```kotlin
 * // product grid: 24 per page by default, at most 60, "newest" first
 * call.pageable(allowedSorts = ProductSorts.names, defaultSort = "newest", defaultSize = 24, maxSize = 60)
 * ```
 * Missing parameters use the defaults; anything invalid becomes a 400 listing every problem.
 */
fun ApplicationCall.pageable(
    allowedSorts: Set<String> = emptySet(),
    defaultSort: String? = null,
    defaultSize: Int = Pageable.DEFAULT_SIZE,
    maxSize: Int = Pageable.MAX_SIZE,
): Result<Pageable, InvalidPagination> {
    val params = request.queryParameters
    return Pageable.makeNew(
        page = params["page"].toIntParam(default = 1),
        size = params["size"].toIntParam(default = defaultSize),
        sort = params["sort"]?.trim()?.ifEmpty { null } ?: defaultSort,
        allowedSorts = allowedSorts,
        maxSize = maxSize,
    )
}

// Missing -> default. Not a number ("abc") -> 0, which Pageable.of rejects with the right failure.
private fun String?.toIntParam(default: Int): Int = if (this == null) default else toIntOrNull() ?: 0

/** The JSON shape of every paginated list (contract: `{ items: [], meta: { page, size, totalItems, totalPages, hasNext } }`). */
@Serializable
data class PageHttpResponse<T>(
    val items: List<T>,
    val meta: PageMetaHttpResponse,
)

@Serializable
data class PageMetaHttpResponse(
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Long,
    val hasNext: Boolean,
)

fun <T, R> Page<T>.toHttpResponse(transform: (T) -> R): PageHttpResponse<R> =
    PageHttpResponse(
        items = items.map(transform),
        meta = PageMetaHttpResponse(page, size, totalItems, totalPages, hasNext),
    )
