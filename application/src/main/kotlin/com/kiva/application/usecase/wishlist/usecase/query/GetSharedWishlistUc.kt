package com.kiva.application.usecase.wishlist.usecase.query

import com.kiva.application.dto.ProductCardDto
import com.kiva.application.usecase.wishlist.WishlistCards
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import com.kiva.domain.aggregate.wishlist.model.valueobject.WishlistShareToken
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository
import java.time.Clock

/**
 * Someone's shared wishlist (`GET /wishlists/shared/{token}`, public; decision 14): its visible products, in its order,
 * at most 200, cards with `isWishlisted = null`, and only the owner's first name. A token that can't be one, one nobody
 * has, a revoked one, or the `wishlistShare` flag off: the same 404, so nothing tells them apart. The token is never
 * logged whole (only its first 6 characters).
 */
class GetSharedWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistShareRepository: WishlistShareRepository,
    private val wishlistRepository: WishlistRepository,
    private val userRepository: UserRepository,
    private val wishlistCards: WishlistCards,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : QueryUseCase<GetSharedWishlistUc.Qry, GetSharedWishlistUc.Shared, GetSharedWishlistUc.Failure> {

    private val logger = getLogger()

    /** @property token the path segment as it came. */
    data class Qry(val token: String) {
        override fun toString(): String = "Qry(token=${token.take(6)}…)"
    }

    /** @property ownerFirstName null when the owner gave none: the HTTP layer says «یکی از دوستان کیوا». */
    data class Shared(val ownerFirstName: String?, val items: List<ProductCardDto>)

    override suspend fun execute(query: Qry): Result<Shared, Failure> {
        logger.info("GetSharedWishlistUc.execute token=${query.token.take(6)}…")
        val token = WishlistShareToken.makeNew(query.token) ?: return Result.failure(Failure.NotFound)
        return transactionManager.inTransaction {
            if (!shopSettingsRepository.get().features.wishlistShare) return@inTransaction Result.failure(Failure.NotFound)
            val share = wishlistShareRepository.findByToken(token) ?: return@inTransaction Result.failure(Failure.NotFound)
            val owner = userRepository.findById(share.userId)
            val cards = wishlistCards.of(wishlistRepository.find(share.userId), clock.instant(), isWishlisted = null).take(WishlistPolicy.MAX_ITEMS)
            Result.success(Shared(owner?.firstName?.value, cards.map { it.card }))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** One answer for every way a link isn't there. */
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
