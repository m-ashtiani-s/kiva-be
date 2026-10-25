package com.kiva.application.usecase.discount

import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.under
import com.kiva.domain.aggregate.category.repository.CategoryRepository
import com.kiva.domain.aggregate.discount.model.DiscountTerms

/**
 * The categories and products a discount code names must be there (roadmap phase 4, section 9.6): one that isn't is
 * 400 on its own index (`categoryIds.2 CATEGORY_NOT_FOUND`), the contract's policy for a reference inside a body. The
 * same check for making and for editing a code; inside the caller's transaction.
 */
class DiscountCodeReferences(
    private val categoryRepository: CategoryRepository,
    private val productQmRepository: ProductQmRepository,
) {
    /** Every name of [terms] that isn't there, under its field and index; empty when all are. */
    fun problemsOf(terms: DiscountTerms): List<ResultFailure> {
        val categories = if (terms.categoryIds.isEmpty()) emptySet() else categoryRepository.findAll().map { it.id }.toSet()
        val products = productQmRepository.findExistingIds(terms.productIds)
        val missingCategories = terms.categoryIds.withIndex().filter { it.value !in categories }.map { Problem.CategoryNotFound.under("${it.index}") }
        val missingProducts = terms.productIds.withIndex().filter { it.value !in products }.map { Problem.ProductNotFound.under("${it.index}") }
        return listOfNotNull(
            missingCategories.takeIf { it.isNotEmpty() }?.under("categoryIds"),
            missingProducts.takeIf { it.isNotEmpty() }?.under("productIds"),
        )
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object CategoryNotFound : Problem("CATEGORY_NOT_FOUND")

        data object ProductNotFound : Problem("PRODUCT_NOT_FOUND")
    }
}
