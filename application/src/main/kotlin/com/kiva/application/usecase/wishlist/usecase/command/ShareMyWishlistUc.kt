package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.application.dto.WishlistShareDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.wishlist.entity.WishlistShare
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareTokenGenerator
import java.time.Clock

/**
 * The public link of the customer's wishlist (`POST /me/wishlist/share`; decision 12): the live one if there is one,
 * else a new token; 200 either way. Under the list's lock (lock 13), so two clicks at once make one link. With the
 * `wishlistShare` flag off: 422 WISHLIST_SHARE_DISABLED. The token is never logged whole.
 */
class ShareMyWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val wishlistShareRepository: WishlistShareRepository,
    private val tokens: WishlistShareTokenGenerator,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) : CommandUseCase<ShareMyWishlistUc.Cmd, WishlistShareDto, ShareMyWishlistUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Cmd(val userId: UserId)

    override suspend fun execute(command: Cmd): Result<WishlistShareDto, Failure> {
        logger.info("ShareMyWishlistUc.execute userId=${command.userId.value}")
        return transactionManager.inTransaction {
            if (!shopSettingsRepository.get().features.wishlistShare) return@inTransaction Result.failure(Failure.Disabled)
            wishlistRepository.lockFor(command.userId)
            val share = wishlistShareRepository.find(command.userId)
                ?: WishlistShare(command.userId, tokens.next(), clock.instant()).also {
                    wishlistShareRepository.insert(it)
                    logger.info("ShareMyWishlistUc: a new link ${it.token} for user ${command.userId.value}")
                }
            Result.success(WishlistShareDto(share.token.value, share.createdOn))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** The shop has sharing switched off (422). */
        data object Disabled : Failure("WISHLIST_SHARE_DISABLED", FailureKind.BUSINESS_RULE)
    }
}
