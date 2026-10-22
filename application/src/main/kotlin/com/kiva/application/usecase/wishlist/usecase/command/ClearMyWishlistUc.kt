package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository

/**
 * Empties the customer's wishlist (`DELETE /me/wishlist`), hidden rows too; the share link stays (the same link, an
 * empty list). Under the list's lock (lock 13). Never fails.
 */
class ClearMyWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
) : CommandUseCase<ClearMyWishlistUc.Cmd, Unit, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Cmd(val userId: UserId)

    override suspend fun execute(command: Cmd): Result<Unit, Nothing> {
        logger.info("ClearMyWishlistUc.execute userId=${command.userId.value}")
        return transactionManager.inTransaction {
            val wishlist = wishlistRepository.lockFor(command.userId)
            if (wishlist.clear()) wishlistRepository.save(wishlist)
            Result.success(Unit)
        }
    }
}
