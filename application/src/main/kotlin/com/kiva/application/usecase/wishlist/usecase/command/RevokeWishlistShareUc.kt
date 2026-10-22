package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository

/**
 * Switches the customer's link off (`DELETE /me/wishlist/share`; decision 12): the old link is 404 at once, the next
 * share makes a new token. Always 204, without a link too and with the flag off. Under the list's lock (lock 13).
 */
class RevokeWishlistShareUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val wishlistShareRepository: WishlistShareRepository,
) : CommandUseCase<RevokeWishlistShareUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Cmd(val userId: UserId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("RevokeWishlistShareUc.execute userId=${command.userId.value}")
        return transactionManager.inTransaction {
            wishlistRepository.lockFor(command.userId)
            wishlistShareRepository.delete(command.userId)
            Result.success(Unit)
        }
    }
}
