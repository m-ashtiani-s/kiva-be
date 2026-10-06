package com.kiva.domain.aggregate.category.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId

interface CategoryRepository {
    /** Every category, in menu order ([Category.sortOrder]), hidden ones included. */
    fun findAll(): List<Category>

    fun findBySlug(slug: Slug): Category?

    fun findById(id: CategoryId): Category?
}
