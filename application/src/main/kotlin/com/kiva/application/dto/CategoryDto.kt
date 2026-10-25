package com.kiva.application.dto

import com.kiva.common.valueobject.SeoMeta
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.product.model.valueobject.BagType

/**
 * A category as the shop and the panel show it, plus how many products it holds (the shop counts those in the shop,
 * the panel every status).
 *
 * @property imagePath the category photo's 800-pixel size, relative to the media root (the HTTP layer adds the public
 *   base), or null without a photo.
 */
data class CategoryDto(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
    val iconBagType: BagType,
    val iconColorKey: String,
    val imageMediaId: String?,
    val imagePath: String?,
    val sortOrder: Int,
    val showInMenu: Boolean,
    val seo: SeoMeta,
    val productCount: Int,
) {
    companion object {
        /** The width of a category's photo on its page and in menus. */
        const val IMAGE_WIDTH = 800

        /** @param image the category's photo, when it has one (looked up by the caller from [Category.imageMediaId]). */
        fun from(category: Category, productCount: Int, image: MediaAsset? = null): CategoryDto =
            CategoryDto(
                id = category.id.value,
                slug = category.slug.value,
                name = category.name.value,
                description = category.description?.value,
                iconBagType = category.icon.bagType,
                iconColorKey = category.icon.colorKey.value,
                imageMediaId = category.imageMediaId?.value,
                imagePath = image?.pathFor(IMAGE_WIDTH),
                sortOrder = category.sortOrder,
                showInMenu = category.showInMenu,
                seo = category.seo,
                productCount = productCount,
            )
    }
}
