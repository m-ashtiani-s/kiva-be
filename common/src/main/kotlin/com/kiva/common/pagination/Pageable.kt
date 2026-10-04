package com.kiva.common.pagination

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.zip

/** One sort instruction: a field name the client may use (`"price"`) and a direction. */
data class Sort(val field: String, val direction: Direction = Direction.ASC) {
    enum class Direction { ASC, DESC }
}

/**
 * Which slice of a list the client wants.
 *
 * - [page] starts at **1** (the first page is page 1, as shown to people).
 * - [size] is 1..[MAX_SIZE]. A bigger size is rejected, not silently reduced, so a client bug shows up.
 * - [sort] only contains fields from the allowed list given to [of]; the client can't sort by any column it likes.
 *
 * Only [of] can create one, so every Pageable in the system is valid.
 */
@ConsistentCopyVisibility
data class Pageable private constructor(
    val page: Int,
    val size: Int,
    val sort: List<Sort>,
) {
    /** How many rows to skip in the database: page 3 of size 10 skips 20. */
    val offset: Long get() = (page - 1).toLong() * size

    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun of(page: Int, size: Int, sort: List<Sort> = emptyList(), allowedSorts: Set<String> = emptySet()): Result<Pageable, InvalidPagination> =
            Result.zip(
                checkPage(page),
                checkSize(size),
                checkSort(sort, allowedSorts),
                ::InvalidPagination,
            ) { p, s, so -> Pageable(p, s, so) }

        private fun checkPage(page: Int): Result<Int, PaginationFailure> =
            if (page >= 1) Result.success(page) else Result.failure(PaginationFailure.PageInvalid)

        private fun checkSize(size: Int): Result<Int, PaginationFailure> =
            if (size in 1..MAX_SIZE) Result.success(size) else Result.failure(PaginationFailure.SizeInvalid)

        private fun checkSort(sort: List<Sort>, allowed: Set<String>): Result<List<Sort>, PaginationFailure> {
            val notAllowed = sort.firstOrNull { it.field !in allowed }
            return if (notAllowed == null) Result.success(sort) else Result.failure(PaginationFailure.SortNotAllowed(notAllowed.field))
        }
    }
}

sealed class PaginationFailure(code: String, field: String) : ResultFailure(code, FailureKind.INVALID, field) {
    data object PageInvalid : PaginationFailure("PAGE_INVALID", "page")
    data object SizeInvalid : PaginationFailure("PAGE_SIZE_INVALID", "size")
    data class SortNotAllowed(val name: String) : PaginationFailure("SORT_NOT_ALLOWED", "sort")
}

data class InvalidPagination(val reasons: List<ResultFailure>) :
    ResultFailure("INVALID_PAGINATION", FailureKind.INVALID, causes = reasons)
