package com.kiva.application.usecase.wishlist.usecase.query

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository

/**
 * Only the ids (`GET /me/wishlist/ids`): the visible products of the customer's list, newest added first, for the
 * hearts on cards the shop sends without them (a cached `/home`) and the header's badge. No paging: 200 at most.
 */
class GetMyWishlistIdsUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
) : QueryUseCase<GetMyWishlistIdsUc.Qry, List<ProductId>, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<List<ProductId>, Nothing> {
        logger.info("GetMyWishlistIdsUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction { Result.success(wishlistRepository.visibleIdsOf(query.userId)) }
    }
}
