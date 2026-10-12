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
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode

/** Chooses how the order ships (`PUT /cart/shipping-method`). Only a method the shop offers can be chosen. */
class ChooseShippingMethodUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<ChooseShippingMethodUc.Cmd, CartChange, ChooseShippingMethodUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller, val method: String)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("ChooseShippingMethodUc.execute caller=${command.caller} method=${command.method}")
        val method = ShippingMethodCode.makeNew(command.method).forField("method")
            .unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }

        return cartCommands.run(command.caller) { context ->
            cartDomainService.chooseShippingMethod(context.cart, method, context.offers, context.settings, context.now)
                .mapOnSuccess { CartCommands.Done() }
                .mapOnFailure { Failure.Refused(it) }
        }
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Not one of the contract's methods (400 VALIDATION_ERROR on `method`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** SHIPPING_METHOD_UNAVAILABLE: switched off, or without a row (COURIER). */
        data class Refused(val failure: Cart.Failure) : Failure(failure.code, failure.kind, meta = failure.meta)
    }
}
