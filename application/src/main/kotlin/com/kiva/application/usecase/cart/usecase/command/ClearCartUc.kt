package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService

/** Empties the cart (`DELETE /cart`): the lines and the reservation switch go, the shipping method stays. */
class ClearCartUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<ClearCartUc.Cmd, CartChange, Nothing> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller)

    override suspend fun execute(command: Cmd): Result<CartChange, Nothing> {
        logger.info("ClearCartUc.execute caller=${command.caller}")
        return cartCommands.run(command.caller) { context ->
            cartDomainService.clearCart(context.cart, context.offers, context.settings, context.now, context.standing)
            Result.success(CartCommands.Done())
        }
    }
}
