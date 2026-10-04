package com.kiva.common.pagination

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PaginationTest {

    private val sortable = setOf("price", "name")

    private fun causesOf(result: Result<Pageable, InvalidPagination>): List<String> =
        assertIs<Result.Failure<InvalidPagination>>(result).failure.causes.map { it.code }

    @Nested
    inner class PageableCreation {
        @Test
        fun `a valid request is accepted`() {
            val pageable = Pageable.of(page = 2, size = 10, sort = listOf(Sort("price", Sort.Direction.DESC)), allowedSorts = sortable)
                .getOrNull()!!

            assertEquals(2, pageable.page)
            assertEquals(10, pageable.size)
            assertEquals(listOf(Sort("price", Sort.Direction.DESC)), pageable.sort)
        }

        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun `page must be at least 1`(page: Int) {
            assertEquals(listOf("PAGE_INVALID"), causesOf(Pageable.of(page, 10)))
        }

        @ParameterizedTest
        @ValueSource(ints = [0, -5, 101, 1_000_000])
        fun `size must be between 1 and 100`(size: Int) {
            assertEquals(listOf("PAGE_SIZE_INVALID"), causesOf(Pageable.of(1, size)))
        }

        @Test
        fun `sizes on the edges are accepted`() {
            assertEquals(1, Pageable.of(1, 1).getOrNull()?.size)
            assertEquals(100, Pageable.of(1, Pageable.MAX_SIZE).getOrNull()?.size)
        }

        @Test
        fun `sorting by a field outside the allowed list is rejected`() {
            val result = Pageable.of(1, 10, listOf(Sort("password")), allowedSorts = sortable)

            val failure = assertIs<Result.Failure<InvalidPagination>>(result).failure
            assertEquals(PaginationFailure.SortNotAllowed("password"), failure.causes.single())
            assertEquals("sort", failure.causes.single().field)
        }

        @Test
        fun `every problem is reported at once`() {
            val result = Pageable.of(0, 500, listOf(Sort("password")), allowedSorts = sortable)
            assertEquals(listOf("PAGE_INVALID", "PAGE_SIZE_INVALID", "SORT_NOT_ALLOWED"), causesOf(result))
        }
    }

    @Nested
    inner class Offset {
        @Test
        fun `first page skips nothing`() {
            assertEquals(0L, Pageable.of(1, 10).getOrNull()?.offset)
        }

        @Test
        fun `page 3 of size 10 skips 20 rows`() {
            assertEquals(20L, Pageable.of(3, 10).getOrNull()?.offset)
        }

        @Test
        fun `offset does not overflow on huge page numbers`() {
            assertEquals(2_147_483_646L * 100, Pageable.of(Int.MAX_VALUE, 100).getOrNull()?.offset)
        }
    }

    @Nested
    inner class PageResult {
        @Test
        fun `total pages rounds up`() {
            assertEquals(3L, Page(listOf<Int>(), page = 1, size = 10, totalItems = 25).totalPages)
            assertEquals(2L, Page(listOf<Int>(), page = 1, size = 10, totalItems = 20).totalPages)
            assertEquals(1L, Page(listOf<Int>(), page = 1, size = 10, totalItems = 1).totalPages)
        }

        @Test
        fun `an empty list has zero pages`() {
            assertEquals(0L, Page(listOf<Int>(), page = 1, size = 10, totalItems = 0).totalPages)
        }

        @Test
        fun `map converts items and keeps the page information`() {
            val page = Page(listOf(1, 2), page = 2, size = 2, totalItems = 4).map { "item $it" }
            assertEquals(Page(listOf("item 1", "item 2"), page = 2, size = 2, totalItems = 4), page)
        }
    }
}
