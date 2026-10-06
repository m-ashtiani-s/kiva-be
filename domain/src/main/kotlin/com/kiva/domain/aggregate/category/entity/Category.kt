package com.kiva.domain.aggregate.category.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName

/**
 * A product category (کیف دوشی، کراس‌بادی، ...): the mega menu, the shop's category chips and the category page.
 *
 * Reference data, stored in a plain table rather than as events: categories change rarely, have no lifecycle,
 * and nobody needs their history.
 *
 * Its identity is [id]; [slug] is unique too and is how the shop addresses it (`/categories/shoulder`).
 *
 * @property sortOrder position in menus, smallest first.
 * @property showInMenu false hides the category from menus while keeping its page reachable.
 */
class Category private constructor(
    val id: CategoryId,
    val slug: Slug,
    val name: CategoryName,
    val description: String?,
    val icon: CategoryIcon,
    val sortOrder: Int,
    val showInMenu: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is Category && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Category(id=${id.value}, slug=${slug.value})"

    companion object {
        fun constructFromPersisted(
            id: CategoryId,
            slug: Slug,
            name: CategoryName,
            description: String?,
            icon: CategoryIcon,
            sortOrder: Int,
            showInMenu: Boolean,
        ): Category = Category(id, slug, name, description, icon, sortOrder, showInMenu)
    }
}
