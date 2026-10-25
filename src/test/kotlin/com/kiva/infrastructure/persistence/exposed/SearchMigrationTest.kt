package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.text.normalizePersian
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * V37 over kiva_test (roadmap step 12.1): the SQL that filled searchName for the rows already there and the projector's
 * normalizePersian give the same text for real names, and the projector writes searchName from then on.
 */
class SearchMigrationTest {

    private val db = TestDatabase.database
    private val catalog = StoredCatalog()

    @AfterEach
    fun deleteWhatWasStored() {
        catalog.deleteAll()
    }

    private fun inSql(text: String): String = transaction(db) {
        exec("SELECT kiva_normalize_persian(?)", listOf(TextColumnType() to text)) { rs -> rs.next(); rs.getString(1) }!!
    }

    @Test
    fun `the SQL and normalizePersian give the same text for real names - half-spaces, Arabic letters, Persian digits, spaces`() {
        listOf(
            "کراس‌بادی نیلا",
            "كيف دوشي ماهک",
            "کوله ۲۴ لیتری  شب‌تاب",
            " کیف ‌ مجلسی‌‌ ستاره ",
            "Tote BAG ٣ جیب",
        ).forEach { name -> assertEquals(name.normalizePersian(), inSql(name), "for «$name»") }
    }

    @Test
    fun `the projector writes searchName with the name normalized`() {
        val product = catalog.newProduct(listOf(NewColor("lilac")))
        val (name, searchName) = transaction(db) {
            ProductQmTbl.selectAll().where { ProductQmTbl.productId eq product.id.value }.single().let { it[ProductQmTbl.name] to it[ProductQmTbl.searchName] }
        }
        assertEquals(name.normalizePersian(), searchName)
    }
}
