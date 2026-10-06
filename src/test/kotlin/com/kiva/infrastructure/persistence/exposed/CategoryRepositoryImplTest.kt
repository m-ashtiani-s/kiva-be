package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Reads the categories that V2__catalog_reference_data.sql seeds into the freshly migrated kiva_test. */
class CategoryRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = CategoryRepositoryImpl()

    @Test
    fun `the 8 seeded categories come back in menu order with their icons`() {
        val categories = transaction(db) {
            repository.findAll().map { Triple(it.slug.value, it.icon.bagType, it.icon.colorKey.value) }
        }

        assertEquals(
            listOf(
                Triple("shoulder", BagType.HOBO, "lilac"),
                Triple("hand", BagType.SATCHEL, "caramel"),
                Triple("cross", BagType.CROSSBODY, "red"),
                Triple("backpack", BagType.BACKPACK, "olive"),
                Triple("party", BagType.CLUTCH, "black"),
                Triple("tote", BagType.TOTE, "cream"),
                Triple("bucket", BagType.BUCKET, "pink"),
                Triple("wallet", BagType.WALLET, "purple"),
            ),
            categories,
        )
    }

    @Test
    fun `findBySlug maps every column`() {
        val cross = transaction(db) { repository.findBySlug(Slug.constructFromPersisted("cross")) }

        assertNotNull(cross)
        assertEquals(3L, cross.id.value)
        assertEquals("cross", cross.slug.value)
        assertEquals("کراس‌بادی", cross.name.value)
        assertNull(cross.description)
        assertEquals(CategoryIcon(BagType.CROSSBODY, ColorKey.constructFromPersisted("red")), cross.icon)
        assertEquals(3, cross.sortOrder)
        assertEquals(true, cross.showInMenu)
    }

    @Test
    fun `findById finds a category by its number, and null for a number nobody has`() {
        val (cross, none) = transaction(db) {
            repository.findById(CategoryId.constructFromPersisted(3)) to repository.findById(CategoryId.constructFromPersisted(99))
        }

        assertEquals("cross", cross?.slug?.value)
        assertNull(none)
    }

    @Test
    fun `findBySlug is null for a slug nobody uses`() {
        assertNull(transaction(db) { repository.findBySlug(Slug.constructFromPersisted("nope")) })
    }
}
