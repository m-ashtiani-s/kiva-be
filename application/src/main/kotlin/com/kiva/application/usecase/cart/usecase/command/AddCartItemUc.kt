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
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/**
 * Adds a color to the cart (`POST /cart/items`); a color already there adds to its line. More than the line may hold
 * is cut, with QUANTITY_REDUCED, as the contract asks.
 */
class AddCartItemUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<AddCartItemUc.Cmd, CartChange, AddCartItemUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller, val variantId: Long, val quantity: Int)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("AddCartItemUc.execute caller=${command.caller} variantId=${command.variantId} quantity=${command.quantity}")

        // Both fields at once, before the transaction, so bad input never takes a database connection.
        val (variantId, quantity) = Result.zip(
            VariantId.makeNew(command.variantId).forField("variantId"),
            CartQuantity.makeNew(command.quantity),
            { Failure.InvalidInput(it) },
        ) { variantId, quantity -> variantId to quantity }.unwrapOr { return Result.failure(it) }

        return cartCommands.run(command.caller, alsoOffersOf = listOf(variantId)) { context ->
            val before = context.cart.lineOf(variantId)?.quantity?.value ?: 0
            cartDomainService.addItem(context.cart, variantId, quantity, context.offers, context.settings, context.now, context.standing)
                .mapOnSuccess { itemId ->
                    val after = context.cart.find(itemId)?.quantity?.value ?: 0
                    CartCommands.Done(affectedItemId = itemId, addedQuantity = maxOf(0, after - before))
                }
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
        /** One or both fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** A rule of the cart said no: VARIANT_NOT_FOUND, PRODUCT_UNAVAILABLE, OUT_OF_STOCK or QUANTITY_EXCEEDS_LIMIT. */
        data class Refused(val failure: Cart.Failure) : Failure(failure.code, failure.kind, failure.field, meta = failure.meta)
    }
}
