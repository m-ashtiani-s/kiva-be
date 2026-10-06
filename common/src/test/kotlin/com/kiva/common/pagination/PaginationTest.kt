package com.kiva.common.pagination

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaginationTest {

    private val sorts = setOf("newest", "price_asc", "price_desc")

    private fun causesOf(result: Result<Pageable, InvalidPagination>): List<String> =
        assertIs<Result.Failure<InvalidPagination>>(result).failure.causes.map { it.code }

    @Nested
    inner class PageableCreation {
        @Test
        fun `a valid request is accepted`() {
            val pageable = Pageable.makeNew(page = 2, size = 10, sort = "price_desc", allowedSorts = sorts).getOrNull()!!

            assertEquals(2, pageable.page)
            assertEquals(10, pageable.size)
            assertEquals("price_desc", pageable.sort)
        }

        @Test
        fun `no sort means the default order`() {
            assertNull(Pageable.makeNew(1, 10).getOrNull()!!.sort)
        }

        @ParameterizedTest
        @ValueSource(ints = [0, -1])
        fun `page must be at least 1`(page: Int) {
            assertEquals(listOf("PAGE_INVALID"), causesOf(Pageable.makeNew(page, 10)))
        }

        @ParameterizedTest
        @ValueSource(ints = [0, -5, 101, 1_000_000])
        fun `size must be between 1 and 100 by default`(size: Int) {
            assertEquals(listOf("PAGE_SIZE_INVALID"), causesOf(Pageable.makeNew(1, size)))
        }

        @Test
        fun `sizes on the edges are accepted`() {
            assertEquals(1, Pageable.makeNew(1, 1).getOrNull()?.size)
            assertEquals(100, Pageable.makeNew(1, Pageable.MAX_SIZE).getOrNull()?.size)
        }

        @Test
        fun `an endpoint can lower the size limit and the failure says what it is`() {
            assertEquals(60, Pageable.makeNew(1, 60, maxSize = 60).getOrNull()?.size)

            val failure = Pageable.makeNew(1, 61, maxSize = 60).failureOrNull()!!.causes.single()
            assertEquals(PaginationFailure.SizeInvalid(60), failure)
            assertEquals(mapOf<String, Any>("maxSize" to 60), failure.meta)
        }

        @Test
        fun `a sort outside the endpoint's options is rejected and the options are listed`() {
            val failure = Pageable.makeNew(1, 10, sort = "password", allowedSorts = sorts).failureOrNull()!!.causes.single()

            assertEquals("SORT_NOT_ALLOWED", failure.code)
            assertEquals("sort", failure.field)
            assertEquals(mapOf<String, Any>("allowed" to listOf("newest", "price_asc", "price_desc")), failure.meta)
        }

        @Test
        fun `every problem is reported at once`() {
            val result = Pageable.makeNew(0, 500, sort = "password", allowedSorts = sorts)
            assertEquals(listOf("PAGE_INVALID", "PAGE_SIZE_INVALID", "SORT_NOT_ALLOWED"), causesOf(result))
        }
    }

    @Nested
    inner class Offset {
        @Test
        fun `first page skips nothing`() {
            assertEquals(0L, Pageable.makeNew(1, 10).getOrNull()?.offset)
        }

        @Test
        fun `page 3 of size 10 skips 20 rows`() {
            assertEquals(20L, Pageable.makeNew(3, 10).getOrNull()?.offset)
        }

        @Test
        fun `offset does not overflow on huge page numbers`() {
            assertEquals(2_147_483_646L * 100, Pageable.makeNew(Int.MAX_VALUE, 100).getOrNull()?.offset)
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
        fun `hasNext is true only before the last page`() {
            assertTrue(Page(listOf<Int>(), page = 2, size = 10, totalItems = 25).hasNext)
            assertFalse(Page(listOf<Int>(), page = 3, size = 10, totalItems = 25).hasNext)
            assertFalse(Page(listOf<Int>(), page = 9, size = 10, totalItems = 25).hasNext)
            assertFalse(Page(listOf<Int>(), page = 1, size = 10, totalItems = 0).hasNext)
        }

        @Test
        fun `map converts items and keeps the page information`() {
            val page = Page(listOf(1, 2), page = 2, size = 2, totalItems = 4).map { "item $it" }
            assertEquals(Page(listOf("item 1", "item 2"), page = 2, size = 2, totalItems = 4), page)
        }
    }
}
