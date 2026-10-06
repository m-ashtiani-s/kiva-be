package com.kiva.domain.aggregate.category.model.valueobject

import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.product.model.valueobject.BagType

/**
 * The illustration of a category in menus: a bag of form [bagType] drawn in color [colorKey].
 * Both parts are already valid on their own, so there is nothing more to check here.
 */
data class CategoryIcon(val bagType: BagType, val colorKey: ColorKey)
