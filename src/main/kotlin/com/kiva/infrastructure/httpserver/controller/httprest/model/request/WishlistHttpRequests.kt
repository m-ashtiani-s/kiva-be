package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import kotlinx.serialization.Serializable

/** The optional body of `PUT /me/wishlist/items/{productId}`: the color the customer was looking at. */
@Serializable
data class WishlistAddHttpRequest(val colorKey: String? = null)

/** The optional body of `POST /me/wishlist/add-to-cart`: only these of the list (none: all of it). */
@Serializable
data class WishlistToCartHttpRequest(val productIds: List<Long?>? = null)
