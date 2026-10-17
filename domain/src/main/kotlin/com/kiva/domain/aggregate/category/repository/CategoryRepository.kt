package com.kiva.domain.aggregate.category.repository

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.category.entity.Category
import com.kiva.domain.aggregate.category.model.valueobject.CategoryId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey

/**
 * The categories, kept as rows (category_tbl). Runs inside the caller's transaction.
 *
 * [insert] and [update] throw com.kiva.common.repository.UniqueTakenException when another category took the slug
 * after the caller checked; [delete] throws com.kiva.common.repository.StillReferencedException when a product got the
 * category after the caller counted none.
 */
interface CategoryRepository {
    /** Every category, in menu order ([Category.sortOrder], then id), hidden ones included. */
    fun findAll(): List<Category>

    fun findBySlug(slug: Slug): Category?

    fun findById(id: CategoryId): Category?

    fun insert(category: Category)

    fun update(category: Category)

    fun delete(id: CategoryId)

    /** How many categories draw their menu icon in [colorKey]: a color in use can't leave the palette. */
    fun countWithIcon(colorKey: ColorKey): Int
}

