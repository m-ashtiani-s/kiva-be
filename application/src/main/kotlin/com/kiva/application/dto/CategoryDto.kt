package com.kiva.application.dto

import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.product.model.valueobject.BagType

/** A category as the shop shows it in menus and on its page, plus how many products it holds. */
data class CategoryDto(
    val id: Long,
    val slug: String,
    val name: String,
    val description: String?,
    val iconBagType: BagType,
    val iconColorKey: String,
    val sortOrder: Int,
    val showInMenu: Boolean,
    val productCount: Int,
) {
    companion object {
        fun from(category: Category, productCount: Int): CategoryDto =
            CategoryDto(
                id = category.id.value,
                slug = category.slug.value,
                name = category.name.value,
                description = category.description,
                iconBagType = category.icon.bagType,
                iconColorKey = category.icon.colorKey.value,
                sortOrder = category.sortOrder,
                showInMenu = category.showInMenu,
                productCount = productCount,
            )
    }
}
