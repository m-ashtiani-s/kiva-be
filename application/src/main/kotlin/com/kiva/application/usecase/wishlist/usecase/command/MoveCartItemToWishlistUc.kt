package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository

/**
 * «انتقال به علاقه‌مندی‌ها» (`POST /cart/items/{itemId}/move-to-wishlist`; roadmap section 14.6, decision 11): a line of
 * the customer's own cart leaves it and its product goes into their wishlist with the line's color (already there:
 * only its color changes), in one transaction: the cart's lock (2), then the list's (13). A full list that hasn't the
 * product is 422 WISHLIST_LIMIT_REACHED and nothing changes: the line stays in the cart.
 *
 * Returns the cart's change with the line taken out (`removedItem`, for «برگردون», which posts it to the cart again and
 * leaves the list alone).
 */
class MoveCartItemToWishlistUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
    private val wishlistRepository: WishlistRepository,
    private val productQmRepository: ProductQmRepository,
) : CommandUseCase<MoveCartItemToWishlistUc.Cmd, CartChange, MoveCartItemToWishlistUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token: a guest's cart has no wishlist. [itemId] the path segment as it came. */
    data class Cmd(val userId: UserId, val itemId: String)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("MoveCartItemToWishlistUc.execute userId=${command.userId.value} itemId=${command.itemId}")
        // A segment that can't be a line id names no line of this cart.
        val itemId = CartItemId.makeNew(command.itemId).getOrNull() ?: return Result.failure(Failure.CartItemNotFound)

        return cartCommands.run(CartCaller.Customer(command.userId)) { context ->
            val line = context.cart.lines.firstOrNull { it.itemId == itemId } ?: return@run Result.failure(Failure.CartItemNotFound)
            val offer = context.offers.getValue(line.variantId)
            val colorKey = productQmRepository.findVariant(line.variantId)?.colorKey
            val wishlist = wishlistRepository.lockFor(command.userId)
            // The full list is checked before the cart is touched: a refusal leaves both as they were.
            if (wishlist.add(offer.productId, colorKey, offer.priceTag.price, context.now) is Result.Failure) return@run Result.failure(Failure.LimitReached)
            val removed = cartDomainService.removeItem(context.cart, itemId, context.offers, context.settings, context.now, context.standing).getOrNull()
                ?: return@run Result.failure(Failure.CartItemNotFound)
            wishlistRepository.save(wishlist)
            logger.info("MoveCartItemToWishlistUc: product ${offer.productId.value} moved from the cart to the list of user ${command.userId.value}")
            Result.success(CartCommands.Done(removedLine = removed))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Not a line of this customer's cart (404). */
        data object CartItemNotFound : Failure("CART_ITEM_NOT_FOUND", FailureKind.NOT_FOUND)

        /** A full list without this product (422, `meta.limit`); the cart is as it was. */
        data object LimitReached : Failure("WISHLIST_LIMIT_REACHED", FailureKind.BUSINESS_RULE, Wishlist.LimitReached.meta)
    }
}
