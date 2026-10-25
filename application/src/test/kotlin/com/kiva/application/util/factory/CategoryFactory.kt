package com.kiva.application.util.factory

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryDescription
import com.kiva.domain.aggregate.category.model.valueobject.CategoryIcon
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.category.model.valueobject.CategoryName
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType

object CategoryFactory {
    fun generate(
        id: Long = 1,
        slug: String = "shoulder",
        name: String = "کیف دوشی",
        description: String? = null,
        iconBagType: BagType = BagType.HOBO,
        iconColorKey: String = "lilac",
        sortOrder: Int = 1,
        showInMenu: Boolean = true,
    ): Category = Category.constructFromPersisted(
        id = CategoryId.constructFromPersisted(id),
        slug = Slug.constructFromPersisted(slug),
        name = CategoryName.constructFromPersisted(name),
        description = description?.let(CategoryDescription::constructFromPersisted),
        icon = CategoryIcon(iconBagType, ColorKey.constructFromPersisted(iconColorKey)),
        sortOrder = sortOrder,
        showInMenu = showInMenu,
    )
}
