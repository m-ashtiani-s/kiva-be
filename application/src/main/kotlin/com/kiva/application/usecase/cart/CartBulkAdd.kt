package com.kiva.application.usecase.cart

import com.kiva.application.dto.CartChange
import com.kiva.common.railway.Result
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.model.Reordered
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Several colors into the cart at once, as far as the shop allows now, with what couldn't go and why (Rule 15):
 * «خرید دوباره» (ReorderUc) and «همه به سبد» (AddWishlistToCartUc) are this one run. The cart is changed the way every
 * cart command changes it (CartCommands: locked, brought up to date, saved); each color is added the way adding one
 * is (CartDomainService.reorder). It never fails: nothing that can be added adds nothing.
 */
class CartBulkAdd(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) {

    /**
     * What a caller asks for, chosen from the offers of this very transaction: [lines] to add, and those it already
     * knows can't go ([skipped]).
     */
    data class Picked(val lines: List<Pair<VariantId, CartQuantity>>, val skipped: List<Reordered.Skipped> = emptyList())

    /** The cart's change, how many bags went in, and every color that couldn't: those the caller knew first, then the cart's. */
    data class Added(val change: CartChange, val addedQuantity: Int, val skipped: List<Reordered.Skipped>)

    /** [pick] runs inside the cart's transaction, with the offers of the cart's lines and of [alsoOffersOf]. */
    suspend fun add(caller: CartCaller, alsoOffersOf: Collection<VariantId>, pick: (CartCommands.Context) -> Picked): Added {
        var done: Reordered? = null
        var known: List<Reordered.Skipped> = emptyList()
        val change = cartCommands.run(caller, alsoOffersOf) { context ->
            val picked = pick(context)
            known = picked.skipped
            done = cartDomainService.reorder(context.cart, picked.lines, context.offers, context.settings, context.now, context.standing)
            Result.success(CartCommands.Done())
        }.unwrapOr { it }
        val reordered = checkNotNull(done)
        return Added(change, reordered.addedQuantity, known + reordered.skipped)
    }
}
