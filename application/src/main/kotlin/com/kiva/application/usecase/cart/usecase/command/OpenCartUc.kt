package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService

/**
 * Looking at the cart (`GET /cart`), which is a change: the cart is brought up to date with the shop and recorded as
 * seen, so each issue is told once. A guest without a known token gets a new, empty cart and its token.
 * The route then builds the cart with GetCartUc.
 */
class OpenCartUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<OpenCartUc.Cmd, CartChange, Nothing> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller)

    override suspend fun execute(command: Cmd): Result<CartChange, Nothing> {
        logger.info("OpenCartUc.execute caller=${command.caller}")
        return cartCommands.run(command.caller) { context ->
            cartDomainService.openCart(context.cart, context.offers, context.settings, context.now)
            Result.success(CartCommands.Done())
        }
    }
}
