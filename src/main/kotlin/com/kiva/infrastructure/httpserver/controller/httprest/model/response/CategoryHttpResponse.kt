package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CategoryDto
import com.kiva.infrastructure.media.MediaUrls
import kotlinx.serialization.Serializable

/**
 * Contract schema `Category`, every field always. `imageUrl` is the category photo's 800-pixel size; `icon.imageUrl`
 * is always null (no upload field for it). `seo` falls back to the category's own name and description, and its
 * canonical address is always the slug's.
 */
@Serializable
data class CategoryHttpResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: CategoryIconHttpResponse,
    val imageUrl: String?,
    val productCount: Int,
    val sortOrder: Int,
    val showInMenu: Boolean,
    val seo: CategorySeoHttpResponse,
) {
    companion object {
        /** @param siteUrl the shop's address (`https://kiva.ir`), for the canonical address. */
        fun from(dto: CategoryDto, urls: MediaUrls, siteUrl: String): CategoryHttpResponse {
            val imageUrl = dto.imagePath?.let(urls::of)
            return CategoryHttpResponse(
                id = dto.id,
                slug = dto.slug,
                name = dto.name,
                description = dto.description,
                icon = CategoryIconHttpResponse(bagType = dto.iconBagType.name, colorKey = dto.iconColorKey, imageUrl = null),
                imageUrl = imageUrl,
                productCount = dto.productCount,
                sortOrder = dto.sortOrder,
                showInMenu = dto.showInMenu,
                seo = CategorySeoHttpResponse(
                    title = dto.seo.title ?: "${dto.name} | کیوا",
                    description = dto.seo.description ?: dto.description,
                    canonicalUrl = "${siteUrl.trimEnd('/')}/categories/${dto.slug}",
                    ogImageUrl = imageUrl,
                    noIndex = dto.seo.noIndex,
                ),
            )
        }
    }
}

@Serializable
data class CategoryIconHttpResponse(val bagType: String, val colorKey: String, val imageUrl: String?)

/** Contract schema `SeoMeta` for a category page: what search engines see. */
@Serializable
data class CategorySeoHttpResponse(
    val title: String,
    val description: String?,
    val canonicalUrl: String,
    val ogImageUrl: String?,
    val noIndex: Boolean,
)

/**
 * Contract schema `AdminCategory`: [CategoryHttpResponse] plus the photo's media id, so the panel's edit form knows
 * which photo it has. Its `productCount` counts products of every status.
 */
@Serializable
data class AdminCategoryHttpResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
    val icon: CategoryIconHttpResponse,
    val imageUrl: String?,
    val imageMediaId: String?,
    val productCount: Int,
    val sortOrder: Int,
    val showInMenu: Boolean,
    val seo: CategorySeoHttpResponse,
) {
    companion object {
        fun from(dto: CategoryDto, urls: MediaUrls, siteUrl: String): AdminCategoryHttpResponse {
            val shop = CategoryHttpResponse.from(dto, urls, siteUrl)
            return AdminCategoryHttpResponse(
                shop.id, shop.slug, shop.name, shop.description, shop.icon, shop.imageUrl, dto.imageMediaId, shop.productCount, shop.sortOrder,
                shop.showInMenu, shop.seo,
            )
        }
    }
}
