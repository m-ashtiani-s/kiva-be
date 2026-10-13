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

/**
 * Turns the «رزرو ۴ روزه» switch on or off (`PUT /cart/reservation`). On only while a reservation is possible, else
 * RESERVATION_UNAVAILABLE (with `meta.reason`); off always.
 */
class SetCartReservationUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<SetCartReservationUc.Cmd, CartChange, SetCartReservationUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val caller: CartCaller, val enabled: Boolean)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("SetCartReservationUc.execute caller=${command.caller} enabled=${command.enabled}")
        return cartCommands.run(command.caller) { context ->
            cartDomainService.setReservation(context.cart, command.enabled, context.offers, context.settings, context.now, context.standing)
                .mapOnSuccess { CartCommands.Done() }
                .mapOnFailure { Failure.Refused(it) }
        }
    }

    sealed class Failure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** RESERVATION_UNAVAILABLE, its reason in `meta.reason`. */
        data class Refused(val failure: Cart.Failure) : Failure(failure.code, failure.kind, failure.meta)
    }
}
