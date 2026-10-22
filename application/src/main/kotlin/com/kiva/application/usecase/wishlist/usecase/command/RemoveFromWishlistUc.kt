package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.application.dto.WishlistToggleDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.application.usecase.wishlist.productIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository

/**
 * Takes a product out of the customer's wishlist (`DELETE /me/wishlist/items/{productId}`; decision 5), idempotent:
 * always 200 with `wishlisted = false`, also when it wasn't in, and for a draft or an archived product. 404
 * PRODUCT_NOT_FOUND only when it wasn't in and no product ever had this id. Under the list's lock (lock 13).
 */
class RemoveFromWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val productQmRepository: ProductQmRepository,
) : CommandUseCase<RemoveFromWishlistUc.Cmd, WishlistToggleDto, RemoveFromWishlistUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [productId] the path segment as it came. */
    data class Cmd(val userId: UserId, val productId: String)

    override suspend fun execute(command: Cmd): Result<WishlistToggleDto, Failure> {
        val productId = productIdOf(command.productId)
        logger.info("RemoveFromWishlistUc.execute userId=${command.userId.value} productId=${productId?.value ?: "(not an id)"}")
        if (productId == null) return Result.failure(Failure.ProductNotFound)

        return transactionManager.inTransaction {
            val wishlist = wishlistRepository.lockFor(command.userId)
            if (wishlist.remove(productId)) {
                wishlistRepository.save(wishlist)
            } else if (productQmRepository.findBriefs(listOf(productId)).isEmpty()) {
                return@inTransaction Result.failure(Failure.ProductNotFound)
            }
            Result.success(WishlistToggleDto(productId.value, wishlisted = false, count = wishlistRepository.countVisible(command.userId)))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Not in the list, and no product ever had this id. */
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
