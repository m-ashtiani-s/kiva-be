package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.CategoryDto
import kotlinx.serialization.Serializable

/**
 * Contract schema `Category`. Not sent yet: `imageUrl` and `icon.imageUrl` (no uploads yet) and `seo`
 * (no SEO data yet); the client already treats them as optional.
 */
@Serializable
data class CategoryHttpResponse(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String? = null,
    val icon: CategoryIconHttpResponse,
    val productCount: Int,
    val sortOrder: Int,
    val showInMenu: Boolean,
) {
    companion object {
        fun from(dto: CategoryDto) = CategoryHttpResponse(
            id = dto.id,
            slug = dto.slug,
            name = dto.name,
            description = dto.description,
            icon = CategoryIconHttpResponse(bagType = dto.iconBagType.name, colorKey = dto.iconColorKey),
            productCount = dto.productCount,
            sortOrder = dto.sortOrder,
            showInMenu = dto.showInMenu,
        )
    }
}

@Serializable
data class CategoryIconHttpResponse(val bagType: String, val colorKey: String)
