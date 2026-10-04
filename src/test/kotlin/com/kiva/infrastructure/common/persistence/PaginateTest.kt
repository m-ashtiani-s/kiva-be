package com.kiva.infrastructure.common.persistence

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.pagination.Sort
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs against the real `kiva_test` Postgres database. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaginateTest {

    private object Items : Table("paginate_test_items") {
        val id = integer("id")
        val name = varchar("name", 20)
        val price = long("price")
        override val primaryKey = PrimaryKey(id)
    }

    private data class Item(val id: Int, val name: String, val price: Long)

    private val db = TestDatabase.database
    private val sortColumns = mapOf("price" to Items.price, "name" to Items.name)

    // 25 items; prices repeat (only 3 different prices) so the tiebreaker matters.
    private val all = (1..25).map { Item(id = it, name = "item-%02d".format(it), price = (it % 3) * 1000L) }

    @BeforeAll
    fun createAndFill() = transaction(db) {
        SchemaUtils.create(Items)
        Items.batchInsert(all.shuffled()) {
            this[Items.id] = it.id
            this[Items.name] = it.name
            this[Items.price] = it.price
        }
        Unit
    }

    @AfterAll
    fun drop() = transaction(db) { SchemaUtils.drop(Items) }

    private fun page(page: Int, size: Int, vararg sort: Sort): Page<Item> = transaction(db) {
        Items.selectAll().paginate(
            Pageable.of(page, size, sort.toList(), sortColumns.keys).getOrNull()!!,
            sortColumns,
            tiebreaker = Items.id,
        ) { Item(it[Items.id], it[Items.name], it[Items.price]) }
    }

    @Test
    fun `page 3 of size 10 holds the last 5 of 25`() {
        val result = page(3, 10)

        assertEquals(listOf(21, 22, 23, 24, 25), result.items.map { it.id })
        assertEquals(25L, result.totalItems)
        assertEquals(3L, result.totalPages)
    }

    @Test
    fun `a page past the end is empty but still knows the total`() {
        val result = page(9, 10)
        assertEquals(emptyList(), result.items)
        assertEquals(25L, result.totalItems)
    }

    @Test
    fun `sorts by the requested column and direction`() {
        val prices = page(1, 25, Sort("price", Sort.Direction.DESC)).items.map { it.price }
        assertEquals(prices.sortedDescending(), prices)
    }

    @Test
    fun `walking all pages with a tied sort returns every item exactly once`() {
        val seen = (1..9).flatMap { page(it, 3, Sort("price")).items.map(Item::id) }

        assertEquals(25, seen.size)
        assertEquals((1..25).toSet(), seen.toSet())
    }

    @Test
    fun `filters of the original query still apply and are counted`() {
        val result = transaction(db) {
            Items.selectAll().where { Items.price greaterEq 1000L }.paginate(
                Pageable.of(1, 100).getOrNull()!!, sortColumns, tiebreaker = Items.id,
            ) { it[Items.id] }
        }
        assertEquals(all.count { it.price >= 1000L }.toLong(), result.totalItems)
        assertEquals(all.filter { it.price >= 1000L }.map { it.id }, result.items)
    }

    @Test
    fun `an allowed sort without a mapped column is a programming error`() {
        val pageable = Pageable.of(1, 10, listOf(Sort("color")), allowedSorts = setOf("color")).getOrNull()!!
        assertFailsWith<IllegalStateException> {
            transaction(db) { Items.selectAll().paginate(pageable, sortColumns, Items.id) { it[Items.id] } }
        }
    }
}
