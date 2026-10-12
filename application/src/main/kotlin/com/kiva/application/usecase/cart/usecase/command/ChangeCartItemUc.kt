package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Changes a line's quantity, its color, or both (`PATCH /cart/items/{itemId}`). Unlike adding, too many is refused,
 * not cut. A color that already has a line merges into it, and that line's id is the answer's `affectedItemId`.
 */
class ChangeCartItemUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<ChangeCartItemUc.Cmd, CartChange, ChangeCartItemUc.Failure> {

    private val logger = getLogger()

    /** [itemId] the path segment as it arrived; at least one of [quantity] and [variantId]. */
    data class Cmd(val caller: CartCaller, val itemId: String, val quantity: Int?, val variantId: Long?)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("ChangeCartItemUc.execute caller=${command.caller} itemId=${command.itemId} quantity=${command.quantity} variantId=${command.variantId}")

        if (command.quantity == null && command.variantId == null) return Result.failure(Failure.InvalidInput(listOf(Failure.NothingToChange)))
        val (quantity, variantId) = Result.zip(
            command.quantity?.let { CartQuantity.makeNew(it) } ?: Result.success(null),
            command.variantId?.let { VariantId.makeNew(it).forField("variantId") } ?: Result.success(null),
            { Failure.InvalidInput(it) },
        ) { quantity, variantId -> quantity to variantId }.unwrapOr { return Result.failure(it) }
        // A segment that can't be a line id names no line of this cart, like one of another cart.
        val itemId = CartItemId.makeNew(command.itemId).getOrNull() ?: return Result.failure(Failure.Refused(Cart.Failure.CartItemNotFound))

        return cartCommands.run(command.caller, alsoOffersOf = listOfNotNull(variantId)) { context ->
            cartDomainService.changeItem(context.cart, itemId, quantity, variantId, context.offers, context.settings, context.now)
                .mapOnSuccess { CartCommands.Done(affectedItemId = it) }
                .mapOnFailure { Failure.Refused(it) }
        }
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta) {
        /** One or both fields are wrong, or neither was sent; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** The contract asks for at least one of `quantity` and `variantId`. */
        data object NothingToChange : Failure("QUANTITY_OR_VARIANT_REQUIRED", FailureKind.INVALID, field = "quantity")

        /**
         * A rule of the cart said no: CART_ITEM_NOT_FOUND, QUANTITY_EXCEEDS_STOCK, QUANTITY_EXCEEDS_LIMIT, OUT_OF_STOCK,
         * PRODUCT_UNAVAILABLE, or VARIANT_ID_INVALID on `variantId` for a color of another product.
         */
        data class Refused(val failure: Cart.Failure) : Failure(failure.code, failure.kind, failure.field, meta = failure.meta)
    }
}
