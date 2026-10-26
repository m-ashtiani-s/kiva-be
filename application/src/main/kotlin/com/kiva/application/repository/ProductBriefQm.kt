package com.kiva.application.repository

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.ProductStatus
import com.kiva.domain.aggregate.product.model.valueobject.Sku

/**
 * A product named somewhere else (the contract's ProductRef: a review in «نظرات من» or the panel, a purchase awaiting a review), in
 * whatever status it is now, with the first photo of its default color.
 */
data class ProductBriefQm(
    val productId: ProductId,
    val slug: Slug,
    val sku: Sku,
    val name: ProductName,
    val status: ProductStatus,
    val image: UploadedMediaDto?,
)
