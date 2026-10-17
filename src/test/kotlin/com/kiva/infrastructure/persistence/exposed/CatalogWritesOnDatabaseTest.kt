package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.repository.StillReferencedException
import com.kiva.common.repository.UniqueTakenException
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.color.entity.Color
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.color.model.valueobject.ColorName
import com.kiva.domain.aggregate.color.model.valueobject.HexColor
import com.kiva.domain.aggregate.material.entity.Material
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The panel's writes of categories, colors and materials on kiva_test (step 1.3): what is stored comes back, and the
 * database's last words (unique keys, foreign keys, V23's CHECKs) reach the use cases as the exceptions they handle.
 * Every test rolls back, so nothing stays.
 */
class CatalogWritesOnDatabaseTest {

    private val db = TestDatabase.database
    private val categories = CategoryRepositoryImpl()
    private val colors = ColorRepositoryImpl()
    private val materials = MaterialRepositoryImpl()

    private fun <T> rolledBack(block: () -> T): T = transaction(db) {
        try {
            block()
        } finally {
            rollback()
        }
    }

    private fun category(id: CategoryId, slug: String, description: String? = null) = Category.create(
        id, Slug.constructFromPersisted(slug), CategoryName.constructFromPersisted("زنبق"), description?.let(CategoryDescription::constructFromPersisted),
        CategoryIcon(BagType.TOTE, ColorKey.constructFromPersisted("cream")), MediaId.constructFromPersisted("med_" + "b".repeat(22)), 7, false,
        SeoMeta.constructFromPersisted("زنبق | کیوا", "کیف‌های زنبق", true),
    )

    @Test
    fun `a category is stored with its photo and SEO, and an update replaces every value`() {
        val (stored, updated) = rolledBack {
            val id = CategoryIdGeneratorImpl().nextCategoryId()
            categories.insert(category(id, "tst-lily", "کیف‌های گل‌دار"))
            val stored = categories.findById(id)!!
            categories.update(stored.change(Slug.constructFromPersisted("tst-lily2"), stored.name, null, stored.icon, null, 1, true, SeoMeta.NONE))
            stored to categories.findBySlug(Slug.constructFromPersisted("tst-lily2"))!!
        }

        assertEquals("med_" + "b".repeat(22), stored.imageMediaId?.value)
        assertEquals(SeoMeta.constructFromPersisted("زنبق | کیوا", "کیف‌های زنبق", true), stored.seo)
        assertEquals("کیف‌های گل‌دار", stored.description?.value)
        assertEquals(stored.id, updated.id)
        assertNull(updated.imageMediaId)
        assertEquals(SeoMeta.NONE, updated.seo)
        assertEquals(1, updated.sortOrder)
    }

    @Test
    fun `a slug taken by another category is UniqueTakenException - on insert and on update`() {
        rolledBack {
            assertFailsWith<UniqueTakenException> { categories.insert(category(CategoryIdGeneratorImpl().nextCategoryId(), "shoulder")) }
        }
        rolledBack {
            val hand = categories.findBySlug(Slug.constructFromPersisted("hand"))!!
            assertFailsWith<UniqueTakenException> {
                categories.update(hand.change(Slug.constructFromPersisted("shoulder"), hand.name, null, hand.icon, null, 1, true, SeoMeta.NONE))
            }
        }
    }

    @Test
    fun `removing a color a category's icon uses is StillReferencedException`() {
        rolledBack {
            assertFailsWith<StillReferencedException> { colors.delete(ColorKey.constructFromPersisted("lilac")) }
        }
    }

    @Test
    fun `a color is stored with its sortOrder, its key twice is UniqueTakenException, and maxSortOrder sees it`() {
        rolledBack {
            colors.insert(Color.create(ColorKey.constructFromPersisted("tst-teal"), ColorName.constructFromPersisted("سبزآبی"), HexColor.constructFromPersisted("#008080"), false, 99))

            assertEquals(99, colors.maxSortOrder())
            assertEquals("سبزآبی", colors.findByKey(ColorKey.constructFromPersisted("tst-teal"))?.name?.value)
            assertFailsWith<UniqueTakenException> {
                colors.insert(Color.create(ColorKey.constructFromPersisted("tst-teal"), ColorName.constructFromPersisted("x"), HexColor.constructFromPersisted("#000000"), false, 1))
            }
        }
    }

    @Test
    fun `a material is stored with its note and care lines - a taken slug is UniqueTakenException`() {
        rolledBack {
            val id = MaterialIdGeneratorImpl().nextMaterialId()
            materials.insert(
                Material.create(
                    id, Slug.constructFromPersisted("tst-cork"), MaterialName.constructFromPersisted("چوب‌پنبه"), MaterialFamily.OTHER,
                    MaterialNote.constructFromPersisted("سبک"), CareInstructions.constructFromPersisted(listOf("خشک نگه دارید")),
                ),
            )

            val stored = materials.findBySlug(Slug.constructFromPersisted("tst-cork"))!!
            assertEquals(id, stored.id)
            assertEquals("سبک", stored.description?.value)
            assertEquals(listOf("خشک نگه دارید"), stored.careInstructions.items)
            assertFailsWith<UniqueTakenException> { materials.update(stored.change(Slug.constructFromPersisted("satin"), stored.name, stored.family, null, CareInstructions.NONE)) }
        }
    }

    @Test
    fun `V23 refuses a category description over 500 and more than 10 care lines`() {
        rolledBack {
            assertFailsWith<ExposedSQLException> {
                exec("UPDATE category_tbl SET description = repeat('x', 501) WHERE slug = 'shoulder'")
            }
        }
        rolledBack {
            assertFailsWith<ExposedSQLException> {
                exec("UPDATE material_tbl SET \"careInstructions\" = array_fill('x'::text, ARRAY[11]) WHERE slug = 'satin'")
            }
        }
    }

    private fun exec(sql: String) {
        org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager.current().exec(sql)
    }
}
