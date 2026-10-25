package com.kiva.application.usecase.catalog

import com.kiva.application.dto.CategoryDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * Every category in menu order, each with how many products in the shop it holds and its photo (Rule 15): `GET
 * /categories` and the home page's section show the same list. Three reads. Inside the caller's transaction.
 */
class CategoryMenu(
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
    private val mediaRepository: MediaRepository,
) {
    fun all(): List<CategoryDto> {
        val productCounts = productQmRepository.countActiveByCategory()
        val categories = categoryRepository.findAll()
        // Every photo in one query, never one per category.
        val images = mediaRepository.findAll(categories.mapNotNull { it.imageMediaId }).associateBy { it.id }
        return categories.map { CategoryDto.from(it, productCounts[it.slug] ?: 0, it.imageMediaId?.let(images::get)) }
    }
}
