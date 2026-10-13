package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService

/** Takes the discount code off the cart (`DELETE /cart/discount-code`). A cart without one is answered the same. */
class RemoveDiscountCodeUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<RemoveDiscountCodeUc.Cmd, CartChange, Nothing> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller)

    override suspend fun execute(command: Cmd): Result<CartChange, Nothing> {
        logger.info("RemoveDiscountCodeUc.execute caller=${command.caller}")
        return cartCommands.run(command.caller) { context ->
            cartDomainService.removeDiscountCode(context.cart, context.now)
            Result.success(CartCommands.Done())
        }
    }
}
