package com.kiva.domain.aggregate.category.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CategoryTest {

    private fun category(id: Long, slug: String = "shoulder", name: String = "کیف دوشی") =
        Category.constructFromPersisted(
            id = CategoryId.constructFromPersisted(id),
            slug = Slug.constructFromPersisted(slug),
            name = CategoryName.constructFromPersisted(name),
            description = null,
            icon = CategoryIcon(BagType.HOBO, ColorKey.constructFromPersisted("lilac")),
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
    fun `constructFromPersisted keeps every value`() {
        val shoulder = category(1)

        assertEquals(1L, shoulder.id.value)
        assertEquals("shoulder", shoulder.slug.value)
        assertEquals("کیف دوشی", shoulder.name.value)
        assertEquals(null, shoulder.description)
        assertEquals(CategoryIcon(BagType.HOBO, ColorKey.constructFromPersisted("lilac")), shoulder.icon)
        assertEquals(1, shoulder.sortOrder)
        assertEquals(true, shoulder.showInMenu)
    }
}
