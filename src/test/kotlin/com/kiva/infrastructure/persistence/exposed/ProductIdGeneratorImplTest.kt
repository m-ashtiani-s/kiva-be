package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The sequences of V4__product_id_sequences.sql, on kiva_test. */
class ProductIdGeneratorImplTest {

    private val db = TestDatabase.database
    private val ids = ProductIdGeneratorImpl()

    @Test
    fun `each call gives a new, larger number`() {
        val taken = transaction(db) { List(3) { ids.nextProductId().value } }

        assertEquals(taken.distinct().sorted(), taken)
    }

    @Test
    fun `products and colors are numbered apart`() {
        val (before, after) = transaction(db) {
            val before = ids.nextProductId()
            repeat(3) { ids.nextVariantId() }
            before to ids.nextProductId()
        }

        assertEquals(before.value + 1, after.value)
    }

    @Test
    fun `a number taken by a transaction that rolled back is skipped, never handed out again`() {
        val rolledBack = transaction(db) { ids.nextProductId().also { rollback() } }
        val next = transaction(db) { ids.nextProductId() }

        assertTrue(next.value > rolledBack.value)
    }

    @Test
    fun `two requests at the same moment never get the same number`() {
        val pool = Executors.newFixedThreadPool(2)
        val taken = try {
            List(2) { pool.submit<List<Long>> { transaction(db) { List(50) { ids.nextProductId().value } } } }.flatMap { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(100, taken.toSet().size)
    }
}
