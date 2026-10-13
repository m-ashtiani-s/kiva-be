package com.kiva.application.usecase.cart.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.cart.model.policy.CartRetentionPolicy
import com.kiva.domain.aggregate.cart.repository.CartRepository
import java.time.Clock

/**
 * Removes the guests' carts nobody came back to (the daily clean-up, roadmap section 15): empty ones untouched for a
 * day, and any untouched for a month (CartRetentionPolicy). A customer's cart is never removed. Running twice, or on
 * two app instances at once, removes nothing twice.
 */
class DeleteStaleGuestCartsUc(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val clock: Clock,
) : CommandUseCase<DeleteStaleGuestCartsUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    data object Cmd

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("DeleteStaleGuestCartsUc.execute")
        val now = clock.instant()
        return transactionManager.inTransaction {
            val removed = cartRepository.deleteStaleGuestCarts(
                emptyUntouchedSince = now.minus(CartRetentionPolicy.EMPTY_GUEST_CART),
                untouchedSince = now.minus(CartRetentionPolicy.GUEST_CART),
            )
            logger.info("DeleteStaleGuestCartsUc: $removed guest carts removed")
            Result.success(Unit)
        }
    }
}
