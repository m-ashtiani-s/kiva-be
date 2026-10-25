package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.application.dto.WishlistToggleDto
import com.kiva.application.usecase.wishlist.WishlistCards
import com.kiva.application.usecase.wishlist.productIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.wishlist.entity.Wishlist
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import java.time.Clock

/**
 * Saves a product in the customer's wishlist (`PUT /me/wishlist/items/{productId}`; roadmap section 14.6, decisions 2
 * to 4), idempotent: a product in the shop (404 PRODUCT_NOT_FOUND otherwise), with the color the customer was looking
 * at, which must be an active color of it (400 COLOR_NOT_FOUND on `colorKey`). Saved again: only the color changes.
 * At most 200 products (422 WISHLIST_LIMIT_REACHED), under the list's lock (lock 13), so twenty adds at once never
 * make 201.
 *
 * Returns what the heart and the header's badge need: a documented exception to «a command returns only ids», like
 * the cart's commands, because the count is the one at that moment. No change log, no SMS (decision 17).
 */
class AddToWishlistUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val wishlistCards: WishlistCards,
    private val clock: Clock,
) : CommandUseCase<AddToWishlistUc.Cmd, WishlistToggleDto, AddToWishlistUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property productId the path segment as it came; [colorKey] the body's, if any.
     */
    data class Cmd(val userId: UserId, val productId: String, val colorKey: String?)

    override suspend fun execute(command: Cmd): Result<WishlistToggleDto, Failure> {
        val productId = productIdOf(command.productId)
        logger.info("AddToWishlistUc.execute userId=${command.userId.value} productId=${productId?.value ?: "(not an id)"} colorKey=${command.colorKey}")
        val colorKey = command.colorKey?.trim()?.ifEmpty { null }?.let { raw ->
            when (val made = ColorKey.makeNew(raw).forField("colorKey")) {
                is Result.Success -> made.value
                is Result.Failure -> return Result.failure(Failure.InvalidInput(listOf(made.failure)))
            }
        }
        if (productId == null) return Result.failure(Failure.ProductNotFound)

        return transactionManager.inTransaction {
            val card = wishlistCards.cardOf(productId) ?: return@inTransaction Result.failure(Failure.ProductNotFound)
            val price = if (colorKey == null) {
                card.priceTag.price
            } else {
                card.colors.firstOrNull { it.colorKey == colorKey }?.price
                    ?: return@inTransaction Result.failure(Failure.InvalidInput(listOf(FieldFailure("colorKey", Problem.ColorNotFound))))
            }
            val wishlist = wishlistRepository.lockFor(command.userId)
            when (val added = wishlist.add(productId, colorKey, price, clock.instant())) {
                is Result.Success -> logger.info("AddToWishlistUc: product ${productId.value} ${added.value} in the list of user ${command.userId.value}")
                is Result.Failure -> return@inTransaction Result.failure(Failure.LimitReached)
            }
            wishlistRepository.save(wishlist)
            Result.success(WishlistToggleDto(productId.value, wishlisted = true, count = wishlistRepository.countVisible(command.userId)))
        }
    }

    /** What can be wrong with the body; the field is added where it is found. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        /** A color this product doesn't come in now. */
        data object ColorNotFound : Problem("COLOR_NOT_FOUND")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** Not in the shop: a draft, an archived product, or none at all. */
        data object ProductNotFound : Failure("PRODUCT_NOT_FOUND", FailureKind.NOT_FOUND)

        data object LimitReached : Failure("WISHLIST_LIMIT_REACHED", FailureKind.BUSINESS_RULE, meta = Wishlist.LimitReached.meta)
    }
}
