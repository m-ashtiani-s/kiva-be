package com.kiva.domain.aggregate.category.repository

import com.kiva.domain.aggregate.category.model.valueobject.CategoryId

/** Takes the next category id (the identity of category_tbl), so a new category has its id before it is stored. */
interface CategoryIdGenerator {
    fun nextCategoryId(): CategoryId
}
