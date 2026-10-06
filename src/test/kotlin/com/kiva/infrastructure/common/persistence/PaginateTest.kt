package com.kiva.infrastructure.common.persistence

import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.core.SortOrder
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
    private val sortOrders: SortOrders = mapOf(
        "price_asc" to listOf(Items.price to SortOrder.ASC),
        "price_desc" to listOf(Items.price to SortOrder.DESC),
        // An option can use several columns: cheapest first, and among equal prices by name Z..A.
        "cheap_name_desc" to listOf(Items.price to SortOrder.ASC, Items.name to SortOrder.DESC),
    )

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

    private fun page(page: Int, size: Int, sort: String? = null): Page<Item> = transaction(db) {
        Items.selectAll().paginate(
            Pageable.makeNew(page, size, sort, sortOrders.keys).getOrNull()!!,
            sortOrders,
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
    fun `sorts by the requested option`() {
        val prices = page(1, 25, "price_desc").items.map { it.price }
        assertEquals(prices.sortedDescending(), prices)
    }

    @Test
    fun `an option can sort by several columns`() {
        val items = page(1, 25, "cheap_name_desc").items
        assertEquals(all.sortedWith(compareBy<Item> { it.price }.thenByDescending { it.name }), items)
    }

    @Test
    fun `walking all pages with a tied sort returns every item exactly once`() {
        val seen = (1..9).flatMap { page(it, 3, "price_asc").items.map(Item::id) }

        assertEquals(25, seen.size)
        assertEquals((1..25).toSet(), seen.toSet())
    }

    @Test
    fun `filters of the original query still apply and are counted`() {
        val result = transaction(db) {
            Items.selectAll().where { Items.price greaterEq 1000L }.paginate(
                Pageable.makeNew(1, 100).getOrNull()!!, sortOrders, tiebreaker = Items.id,
            ) { it[Items.id] }
        }
        assertEquals(all.count { it.price >= 1000L }.toLong(), result.totalItems)
        assertEquals(all.filter { it.price >= 1000L }.map { it.id }, result.items)
    }

    @Test
    fun `an allowed sort without a mapped column is a programming error`() {
        val pageable = Pageable.makeNew(1, 10, "color_asc", allowedSorts = setOf("color_asc")).getOrNull()!!
        assertFailsWith<IllegalStateException> {
            transaction(db) { Items.selectAll().paginate(pageable, sortOrders, Items.id) { it[Items.id] } }
        }
    }
}
