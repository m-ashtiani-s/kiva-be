package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.tbl.CategoryTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ColorTbl
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The constraints of V1__catalog.sql: the database itself refuses bad reference data, whatever wrote it.
 * Every write here is rolled back, so the seeded data stays as it is for the other tests.
 */
class CatalogMigrationTest {

    private val db = TestDatabase.database

    private fun assertRejected(write: JdbcTransaction.() -> Unit) = transaction(db) {
        assertFailsWith<ExposedSQLException> { write() }
        rollback()
    }

    private fun insertColor(key: String = "teal", name: String = "سبزآبی", hex: String = "#2A8C8C") {
        ColorTbl.insert {
            it[colorKey] = key
            it[ColorTbl.name] = name
            it[ColorTbl.hex] = hex
            it[isLight] = false
            it[sortOrder] = 14
        }
    }

    private fun insertCategory(slug: String = "mini", iconColorKey: String = "lilac") {
        CategoryTbl.insert {
            it[CategoryTbl.slug] = slug
            it[name] = "کیف کوچک"
            it[iconBagType] = BagType.CLUTCH
            it[CategoryTbl.iconColorKey] = iconColorKey
            it[sortOrder] = 9
            it[showInMenu] = true
        }
    }

    @Test
    fun `a valid color and category are accepted (and rolled back)`() {
        transaction(db) {
            insertColor()
            insertCategory(iconColorKey = "teal")
            rollback()
        }
    }

    @Test
    fun `a color key outside the contract format is refused`() = assertRejected { insertColor(key = "Teal") }

    @Test
    fun `a color key can be used only once`() = assertRejected { insertColor(key = "lilac") }

    @Test
    fun `a hex without the hash is refused`() = assertRejected { insertColor(hex = "2A8C8C") }

    @Test
    fun `a blank name is refused`() = assertRejected { insertColor(name = "   ") }

    @Test
    fun `a digits-only slug is refused, so it can never be mistaken for an id`() = assertRejected { insertCategory(slug = "12") }

    @Test
    fun `a category slug can be used only once`() = assertRejected { insertCategory(slug = "shoulder") }

    @Test
    fun `a category icon must use a color of the palette`() = assertRejected { insertCategory(iconColorKey = "teal") }

    @Test
    fun `a material family outside the contract list is refused`() = assertRejected {
        exec("""INSERT INTO material_tbl (slug, name, family) VALUES ('linen', 'کتان', 'LINEN')""")
    }

    @Test
    fun `care instructions default to an empty list`() {
        val stored = transaction(db) {
            exec("""INSERT INTO material_tbl (slug, name, family) VALUES ('linen', 'کتان', 'COTTON')""")
            val care = MaterialRepositoryImpl().findAll().single { it.slug.value == "linen" }.careInstructions.items
            rollback()
            care
        }
        assertEquals(emptyList(), stored)
    }
}
