package com.kiva.application.usecase.wishlist.usecase.query

import com.kiva.application.dto.WishlistDto
import com.kiva.application.dto.WishlistShareDto
import com.kiva.application.usecase.wishlist.WishlistCards
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository
import java.time.Clock

/**
 * «علاقه‌مندی‌ها» (`GET /me/wishlist`; roadmap section 14.6): the visible products of the customer's list, newest added
 * first, a page of them (48, at most 100), each with its card (`isWishlisted = true`), the color and price it was
 * saved with, and its price drop. [WishlistDto.count] and [WishlistDto.inStockCount] are over the whole list, not the
 * page; the share link only with the `wishlistShare` flag on.
 */
class GetMyWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val wishlistShareRepository: WishlistShareRepository,
    private val wishlistCards: WishlistCards,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetMyWishlistUc.Qry, WishlistDto, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token; [pageable] checked by the route (no sort of its own). */
    data class Qry(val userId: UserId, val pageable: Pageable)

    override suspend fun execute(query: Qry): Result<WishlistDto, Nothing> {
        logger.info("GetMyWishlistUc.execute userId=${query.userId.value} page=${query.pageable.page}")
        return transactionManager.inTransaction {
            val seen = wishlistCards.of(wishlistRepository.find(query.userId), clock.instant(), isWishlisted = true)
            val pageable = query.pageable
            val items = seen.drop(minOf(pageable.offset, seen.size.toLong()).toInt()).take(pageable.size).map { it.item() }
            val share = if (shopSettingsRepository.get().features.wishlistShare) {
                wishlistShareRepository.find(query.userId)?.let { WishlistShareDto(it.token.value, it.createdOn) }
            } else {
                null
            }
            Result.success(WishlistDto(Page(items, pageable.page, pageable.size, seen.size.toLong()), seen.size, seen.count { it.inStock }, share))
        }
    }
}
