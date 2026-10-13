package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartDomainService

/**
 * Takes a guest's cart into the customer's after a login (`POST /cart/merge`, and `POST /auth/otp/verify` with
 * `guestCartToken`, which runs it right after the login commits, in a transaction of its own). The guest cart is
 * deleted, so its token reaches nothing any more.
 *
 * It never fails: a token that reaches no guest cart (none came, malformed, unknown, merged already) merges nothing,
 * and the answer is the customer's cart, brought up to date as when they look at it. So a merge done twice (verify
 * and then this endpoint, or two tabs) is harmless, a stale token in the browser never spoils a login, and no answer
 * tells which tokens have a cart. The route then builds the cart with GetCartUc.
 */
class MergeGuestCartUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<MergeGuestCartUc.Cmd, CartChange, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token, or of the login just made; [guestCartToken] as it arrived. */
    data class Cmd(val userId: UserId, val guestCartToken: String?) {
        // The token opens a cart: never in a log.
        override fun toString(): String = "Cmd(userId=${userId.value}, guestCartToken=${if (guestCartToken == null) "none" else "****"})"
    }

    override suspend fun execute(command: Cmd): Result<CartChange, Nothing> {
        logger.info("MergeGuestCartUc.execute $command")
        return cartCommands.runMerge(command.userId, command.guestCartToken) { context, guest ->
            cartDomainService.mergeGuestCart(context.cart, guest, context.offers, context.settings, context.now, context.standing)
        }
    }
}
