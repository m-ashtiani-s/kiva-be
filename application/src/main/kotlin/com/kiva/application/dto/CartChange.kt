package com.kiva.application.dto

import com.kiva.domain.aggregate.cart.model.CartIssueCode
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken

/**
 * What a cart command did. The route then builds the whole cart with GetCartUc, as after every command.
 *
 * The one documented exception to "a command returns only an id" (roadmap section 25), for the same reason as
 * IssuedSession: [newToken] and [issues] exist only at this moment. A new guest token is shown to the browser once and
 * only its hash is kept; an issue is recorded as seen by the very command that found it, so no query can read either
 * of them later.
 *
 * @property newToken the token of a guest cart made by this request, for the `X-Cart-Token` header; null otherwise.
 *   Its toString hides it.
 * @property affectedItemId the line the change ended on (after a merge of two colors, the one that remains).
 * @property addedQuantity how many more the affected line holds after an add; 0 when it couldn't take more.
 * @property removedItem the line a remove took out, for «برگردون».
 */
data class CartChange(
    val cartId: CartPublicId,
    val newToken: CartToken?,
    val issues: List<CartIssueDto>,
    val affectedItemId: CartItemId? = null,
    val addedQuantity: Int? = null,
    val removedItem: RemovedItemDto? = null,
)

/**
 * A CartIssue with the names its message needs; the Persian text is the HTTP layer's. The names are there even for a
 * line that is gone. A shipping method change names nothing: its message names the cart's method now.
 */
data class CartIssueDto(
    val code: CartIssueCode,
    val itemId: String?,
    val productName: String?,
    val colorName: String?,
    val previousValue: Long?,
    val currentValue: Long?,
)

/** The line a remove took out: what the client needs to add it back, and the names for the message. */
data class RemovedItemDto(val variantId: Long, val quantity: Int, val productName: String, val colorName: String)
