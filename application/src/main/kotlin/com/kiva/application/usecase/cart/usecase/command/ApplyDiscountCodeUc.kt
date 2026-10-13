package com.kiva.application.usecase.cart.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.discount.Discounts
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.discount.DiscountDomainService
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText

/**
 * Applies a discount code to the cart (`POST /cart/discount-code`, roadmap section 14), in place of any it had. The code
 * is trimmed, its digits made Latin and its letters upper case («kiva10 » is KIVA10). Nothing typed is a 400 on `code`;
 * a code that doesn't apply is 422 with the first reason, in DiscountDomainService's order (DISCOUNT_*), and the cart
 * keeps the code it had. The cart is brought up to date first, as by any change that depends on the shop.
 */
class ApplyDiscountCodeUc(
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
    private val discounts: Discounts,
) : CommandUseCase<ApplyDiscountCodeUc.Cmd, CartChange, ApplyDiscountCodeUc.Failure> {

    private val logger = getLogger()

    /** [code] as it arrived. */
    data class Cmd(val caller: CartCaller, val code: String?)

    override suspend fun execute(command: Cmd): Result<CartChange, Failure> {
        logger.info("ApplyDiscountCodeUc.execute caller=${command.caller} code=${command.code}")
        val code = when (val text = DiscountCodeText.makeNew(command.code.orEmpty())) {
            is Result.Success -> text.value
            is Result.Failure -> return Result.failure(
                when (val failure = text.failure) {
                    DiscountCodeText.Failure.Required -> Failure.InvalidInput(listOf(failure))
                    DiscountCodeText.Failure.Invalid -> Failure.Refused(DiscountDomainService.Failure.Invalid)
                },
            )
        }

        return cartCommands.run(command.caller) { context ->
            val userId = (context.cart.owner as? CartOwner.User)?.userId
            cartDomainService.applyDiscountCode(context.cart, code, context.offers, context.settings, context.now, context.standing) { cart ->
                discounts.check(code, userId, discounts.linesOf(cart, context.offers), context.now)
            }
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
        /** Nothing typed (400 VALIDATION_ERROR on `code`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** The code doesn't apply (422 DISCOUNT_*), `meta.minSubtotal` with DISCOUNT_MIN_SUBTOTAL_NOT_MET. */
        data class Refused(val failure: DiscountDomainService.Failure) : Failure(failure.code, failure.kind, meta = failure.meta)
    }
}
