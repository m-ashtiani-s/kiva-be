package com.kiva.domain.aggregate.category.entity

import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CategoryTest {

    private val lilacHobo = CategoryIcon(BagType.HOBO, ColorKey.constructFromPersisted("lilac"))

    private fun category(id: Long, slug: String = "shoulder", name: String = "کیف دوشی") =
        Category.constructFromPersisted(
            id = CategoryId.constructFromPersisted(id),
            slug = Slug.constructFromPersisted(slug),
            name = CategoryName.constructFromPersisted(name),
            description = null,
            icon = lilacHobo,
            sortOrder = 1,
            showInMenu = true,
        )

    @Test
    fun `two categories with the same id are the same category, even after a rename`() {
        val before = category(1)
        val after = category(1, slug = "shoulder-bags", name = "کیف‌های دوشی")

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `categories with different ids are different categories`() {
        assertNotEquals(category(1), category(2))
    }

    @Test
    fun `constructFromPersisted keeps every value - no photo and no SEO of its own unless given`() {
        val shoulder = category(1)

        assertEquals(1L, shoulder.id.value)
        assertEquals("shoulder", shoulder.slug.value)
        assertEquals("کیف دوشی", shoulder.name.value)
        assertEquals(null, shoulder.description)
        assertEquals(lilacHobo, shoulder.icon)
        assertEquals(1, shoulder.sortOrder)
        assertEquals(true, shoulder.showInMenu)
        assertEquals(null, shoulder.imageMediaId)
        assertEquals(SeoMeta.NONE, shoulder.seo)
    }

    @Test
    fun `create keeps every value it is given`() {
        val photo = MediaId.constructFromPersisted("med_" + "a".repeat(22))
        val seo = SeoMeta.constructFromPersisted("کیف دوشی زنانه", null, true)

        val category = Category.create(
            CategoryId.constructFromPersisted(9), Slug.constructFromPersisted("mini"), CategoryName.constructFromPersisted("مینی"),
            CategoryDescription.constructFromPersisted("کیف‌های کوچک"), lilacHobo, photo, 3, false, seo,
        )

        assertEquals(9L, category.id.value)
        assertEquals("کیف‌های کوچک", category.description?.value)
        assertEquals(photo, category.imageMediaId)
        assertEquals(3, category.sortOrder)
        assertEquals(false, category.showInMenu)
        assertEquals(seo, category.seo)
    }

    @Test
    fun `change replaces every value and keeps the id - the same values again are the same values`() {
        val before = category(1)

        val after = before.change(
            Slug.constructFromPersisted("shoulder-bags"), before.name, before.description, before.icon, before.imageMediaId, 2, before.showInMenu, before.seo,
        )
        val same = before.change(before.slug, before.name, before.description, before.icon, before.imageMediaId, before.sortOrder, before.showInMenu, before.seo)

        assertEquals(before.id, after.id)
        assertEquals("shoulder-bags", after.slug.value)
        assertEquals(2, after.sortOrder)
        assertFalse(after.sameValuesAs(before))
        assertTrue(same.sameValuesAs(before))
    }
}
