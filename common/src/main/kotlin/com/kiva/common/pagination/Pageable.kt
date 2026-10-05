package com.kiva.common.pagination

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.zip

/**
 * Which slice of a list the client wants.
 *
 * - [page] starts at **1** (the first page is page 1, as shown to people).
 * - [size] is 1..maxSize. A bigger size is rejected, not silently reduced, so a client bug shows up.
 *   Each endpoint can choose its own limit (the product grid allows up to 60, most lists 100).
 * - [sort] is one named option from the endpoint's list (`newest`, `price_asc`, ...), or null for the default order.
 *   What each name means in the database is decided by the repository.
 *
 * Only [of] can create one, so every Pageable in the system is valid.
 */
@ConsistentCopyVisibility
data class Pageable private constructor(
    val page: Int,
    val size: Int,
    val sort: String?,
) {
    /** How many rows to skip in the database: page 3 of size 10 skips 20. */
    val offset: Long get() = (page - 1).toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun of(
            page: Int,
            size: Int,
            sort: String? = null,
            allowedSorts: Set<String> = emptySet(),
            maxSize: Int = MAX_SIZE,
        ): Result<Pageable, InvalidPagination> =
            Result.zip(
                checkPage(page),
                checkSize(size, maxSize),
                checkSort(sort, allowedSorts),
                ::InvalidPagination,
            ) { p, s, so -> Pageable(p, s, so) }

        private fun checkPage(page: Int): Result<Int, PaginationFailure> =
            if (page >= 1) Result.success(page) else Result.failure(PaginationFailure.PageInvalid)

        private fun checkSize(size: Int, maxSize: Int): Result<Int, PaginationFailure> =
            if (size in 1..maxSize) Result.success(size) else Result.failure(PaginationFailure.SizeInvalid(maxSize))

        private fun checkSort(sort: String?, allowed: Set<String>): Result<String?, PaginationFailure> =
            if (sort == null || sort in allowed) Result.success(sort) else Result.failure(PaginationFailure.SortNotAllowed(allowed))
    }
}

sealed class PaginationFailure(code: String, field: String, meta: Map<String, Any> = emptyMap()) :
    ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
    data object PageInvalid : PaginationFailure("PAGE_INVALID", "page")

    data class SizeInvalid(val maxSize: Int) : PaginationFailure("PAGE_SIZE_INVALID", "size", mapOf("maxSize" to maxSize))

    /** Tells the client which options exist, sorted so the response is stable. */
    data class SortNotAllowed(val allowed: Set<String>) :
        PaginationFailure("SORT_NOT_ALLOWED", "sort", mapOf("allowed" to allowed.sorted()))
}

data class InvalidPagination(val reasons: List<ResultFailure>) :
    ResultFailure("INVALID_PAGINATION", FailureKind.INVALID, causes = reasons)
