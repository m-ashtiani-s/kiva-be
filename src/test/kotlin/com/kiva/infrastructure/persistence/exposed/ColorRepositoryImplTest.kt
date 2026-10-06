package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads the palette that V2__catalog_reference_data.sql seeds into the freshly migrated kiva_test. */
class ColorRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = ColorRepositoryImpl()

    @Test
    fun `the 13 seeded colors come back in palette order`() {
        val keys = transaction(db) { repository.findAll().map { it.key.value } }

        assertEquals(
            listOf("black", "cream", "brown", "lilac", "gray", "olive", "red", "pink", "blue", "white", "caramel", "navy", "purple"),
            keys,
        )
    }

    @Test
    fun `every column is mapped`() {
        val lilac = transaction(db) { repository.findAll().single { it.key.value == "lilac" } }

        assertEquals("یاسی", lilac.name.value)
        assertEquals("#C8B6E2", lilac.hex.value)
        assertEquals(true, lilac.isLight)
        assertEquals(4, lilac.sortOrder)
    }

    @Test
    fun `findByKey finds a palette color, and null for a key nobody uses`() {
        val (lilac, teal) = transaction(db) {
            repository.findByKey(ColorKey.constructFromPersisted("lilac")) to repository.findByKey(ColorKey.constructFromPersisted("teal"))
        }

        assertEquals("یاسی", lilac?.name?.value)
        assertNull(teal)
    }

    @Test
    fun `exactly the light swatches of the contract are marked light`() {
        val light = transaction(db) { repository.findAll().filter { it.isLight }.map { it.key.value }.toSet() }

        assertEquals(setOf("white", "cream", "pink", "lilac", "gray"), light)
    }
}
