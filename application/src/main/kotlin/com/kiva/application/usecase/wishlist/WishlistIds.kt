package com.kiva.application.usecase.wishlist

import com.kiva.common.text.normalizeDigits
import com.kiva.domain.aggregate.product.model.valueobject.ProductId

/** A product id from a path segment as it came: null when it can't be one (the use case's 404). */
internal fun productIdOf(raw: String): ProductId? = raw.trim().normalizeDigits().toLongOrNull()?.let { ProductId.makeNew(it).getOrNull() }
