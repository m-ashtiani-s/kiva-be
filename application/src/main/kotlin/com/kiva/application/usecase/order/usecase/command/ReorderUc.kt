package com.kiva.application.usecase.order.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.model.Reordered
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode

/**
 * «خرید دوباره» (`POST /me/orders/{orderCode}/reorder`): the bags of an earlier order of the customer's, whatever its
 * status (an expired one is the usual case), into their cart as far as the shop allows now (CartDomainService.reorder).
 * The cart is changed the way every cart command changes it (CartCommands): locked, brought up to date, saved.
 *
 * Returns the cart's change and what the reorder put in or skipped: a documented exception to «a command returns only
 * ids», like the cart's own commands, because what was skipped exists only at that moment. The route builds the cart
 * with GetCartUc.
 */
class ReorderUc(
    private val transactionManager: TransactionManager,
    private val orderQmRepository: OrderQmRepository,
    private val cartCommands: CartCommands,
    private val cartDomainService: CartDomainService,
) : CommandUseCase<ReorderUc.Cmd, ReorderUc.Reorder, ReorderUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property orderCode the path's code, as it came.
     */
    data class Cmd(val userId: UserId, val orderCode: String)

    /**
     * @property addedQuantity how many bags went into the cart.
     * @property skipped the order's colors that couldn't, each with the product's id and name as bought, and why.
     */
    data class Reorder(val change: CartChange, val addedQuantity: Int, val skipped: List<Skipped>)

    data class Skipped(val productId: Long, val name: String, val reason: Reordered.Reason)

    override suspend fun execute(command: Cmd): Result<Reorder, Failure> {
        val code = OrderCode.makeNew(command.orderCode).getOrNull()
        // A code from the path may be anything: only one that can be a code is written to the log.
        logger.info("ReorderUc.execute userId=${command.userId.value} orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        val lines = transactionManager.inTransaction {
            orderQmRepository.findOfCustomer(command.userId, code)?.let { Result.success(it.lines) } ?: Result.failure(Failure.OrderNotFound)
        }.unwrapOr { return Result.failure(it) }

        var reordered: Reordered? = null
        val change = cartCommands.run(CartCaller.Customer(command.userId), alsoOffersOf = lines.map { it.variantId }) { context ->
            // An order's line holds at most what a cart line may: it came from one.
            val asked = lines.map { it.variantId to CartQuantity.constructFromPersisted(minOf(it.quantity.value, CartQuantity.MAX)) }
            reordered = cartDomainService.reorder(context.cart, asked, context.offers, context.settings, context.now)
            Result.success(CartCommands.Done())
        }.unwrapOr { it }

        val done = checkNotNull(reordered)
        logger.info("ReorderUc: order ${code.value} put ${done.addedQuantity} bags in the cart of user ${command.userId.value}, skipped ${done.skipped}")
        return Result.success(Reorder(change, done.addedQuantity, done.skipped.map { skippedOf(it, lines) }))
    }

    private fun skippedOf(skipped: Reordered.Skipped, lines: List<OrderLine>): Skipped {
        val line = lines.first { it.variantId == skipped.variantId }
        return Skipped(line.productId.value, line.item.name.value, skipped.reason)
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
