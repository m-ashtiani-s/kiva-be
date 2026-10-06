package com.kiva.application.repository

import com.kiva.common.text.normalizeDigits
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.Sku

/**
 * How a product's page is asked for (`/products/{idOrSlug}`): by its number (`1`), its code (`KV-1001`) or its slug
 * (`mahak-shoulder-bag`). The three never look alike, so telling them apart needs no guessing: digits only is a
 * number; a capital letter makes a code; anything else is a slug (always lowercase, with at least one letter).
 */
sealed interface ProductRef {
    data class ById(val id: ProductId) : ProductRef

    data class BySku(val sku: Sku) : ProductRef

    data class BySlug(val slug: Slug) : ProductRef

    companion object {
        private val DIGITS = Regex("[0-9]+")

        /** Null when [raw] is none of the three, so it can name no product. Persian digits count as digits. */
        fun parse(raw: String): ProductRef? {
            val text = raw.trim().normalizeDigits()
            return when {
                DIGITS.matches(text) -> text.toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }?.let(::ById)
                text.any { it.isUpperCase() } -> Sku.makeNew(text).getOrNull()?.let(::BySku)
                else -> Slug.makeNew(text).getOrNull()?.let(::BySlug)
            }
        }
    }
}
