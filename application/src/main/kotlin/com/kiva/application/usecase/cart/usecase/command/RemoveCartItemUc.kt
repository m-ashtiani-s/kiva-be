package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId

/**
 * Takes a line out of the cart (`DELETE /cart/items/{itemId}`). The answer's `removedItem` lets the client offer
 * «برگردون»: posting its color and quantity again.
 */
class RemoveCartItemUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<RemoveCartItemUc.Cmd, CartChange, RemoveCartItemUc.Failure> {

    private val logger = getLogger()

    /** [itemId] the path segment as it arrived. */
    data class Cmd(val caller: CartCaller, val itemId: String)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("RemoveCartItemUc.execute caller=${command.caller} itemId=${command.itemId}")
        // A segment that can't be a line id names no line of this cart, like one of another cart.
        val itemId = CartItemId.makeNew(command.itemId).getOrNull() ?: return Result.failure(Failure.Refused(Cart.Failure.CartItemNotFound))

        return cartCommands.run(command.caller) { context ->
            cartDomainService.removeItem(context.cart, itemId, context.offers, context.settings, context.now)
                .mapOnSuccess { CartCommands.Done(removedLine = it) }
                .mapOnFailure { Failure.Refused(it) }
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** CART_ITEM_NOT_FOUND: not a line of this cart, whether of another cart or of none. */
        data class Refused(val failure: Cart.Failure) : Failure(failure.code, failure.kind, failure.meta)
    }
}
