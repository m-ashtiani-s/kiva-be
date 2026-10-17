package com.kiva.domain.aggregate.category.entity

import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.media.model.valueobject.MediaId

/**
 * A product category (کیف دوشی، کراس‌بادی، ...): the mega menu, the shop's category chips and the category page.
 *
 * Reference data, stored in a plain table rather than as events: categories change rarely, have no lifecycle,
 * and nobody needs their history (the panel's change log keeps who changed what for 90 days).
 *
 * Its identity is [id]; [slug] is unique too and is how the shop addresses it (`/categories/shoulder`). The slug may
 * change: the read model follows it (ON UPDATE CASCADE), old links stop working.
 *
 * Rules it keeps: none between its fields; each value object keeps its own. It can't know whether its slug is free,
 * whether its icon's color is in the palette, or whether its image was uploaded for a category: the use case checks
 * those against the repositories.
 *
 * @property imageMediaId the category's photo (a media of purpose CATEGORY), or null.
 * @property sortOrder position in menus, smallest first.
 * @property showInMenu false hides the category from menus while keeping its page reachable.
 */
class Category private constructor(
    val id: CategoryId,
    val slug: Slug,
    val name: CategoryName,
    val description: CategoryDescription?,
    val icon: CategoryIcon,
    val imageMediaId: MediaId?,
    val sortOrder: Int,
    val showInMenu: Boolean,
    val seo: SeoMeta,
) {
    /** Every value of the panel's form, at once: `PUT` replaces them all. The same id, so the same category. */
    fun change(
        slug: Slug,
        name: CategoryName,
        description: CategoryDescription?,
        icon: CategoryIcon,
        imageMediaId: MediaId?,
        sortOrder: Int,
        showInMenu: Boolean,
        seo: SeoMeta,
    ): Category = Category(id, slug, name, description, icon, imageMediaId, sortOrder, showInMenu, seo)

    /** Whether [other] holds exactly the same values (equality is by [id] only). */
    fun sameValuesAs(other: Category): Boolean =
        slug == other.slug && name == other.name && description == other.description && icon == other.icon &&
            imageMediaId == other.imageMediaId && sortOrder == other.sortOrder && showInMenu == other.showInMenu && seo == other.seo

    override fun equals(other: Any?): Boolean = other is Category && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Category(id=${id.value}, slug=${slug.value})"

    companion object {
        /** A new category from the panel, with an id taken from CategoryIdGenerator. */
        fun create(
            id: CategoryId,
            slug: Slug,
            name: CategoryName,
            description: CategoryDescription?,
            icon: CategoryIcon,
            imageMediaId: MediaId?,
            sortOrder: Int,
            showInMenu: Boolean,
            seo: SeoMeta,
        ): Category = Category(id, slug, name, description, icon, imageMediaId, sortOrder, showInMenu, seo)

        fun constructFromPersisted(
            id: CategoryId,
            slug: Slug,
            name: CategoryName,
            description: CategoryDescription?,
            icon: CategoryIcon,
            sortOrder: Int,
            showInMenu: Boolean,
            imageMediaId: MediaId? = null,
            seo: SeoMeta = SeoMeta.NONE,
        ): Category = Category(id, slug, name, description, icon, imageMediaId, sortOrder, showInMenu, seo)
    }
}
