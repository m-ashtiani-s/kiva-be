package com.kiva.application.usecase.wishlist.usecase.command

import com.kiva.application.dto.CartChange
import com.kiva.application.usecase.cart.CartBulkAdd
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.wishlist.WishlistCards
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TooManyItems
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.model.Reordered
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.wishlist.model.policy.WishlistPolicy
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import java.time.Clock

/**
 * «همه‌ی موجودها به سبد» (`POST /me/wishlist/add-to-cart`; roadmap section 14.6, decision 10): the visible products of
 * the customer's list (or only those of `productIds` that are in it), in the list's order, one of each into their
 * cart: the saved color if it is on and left, else the first color left in display order. None left: OUT_OF_STOCK; no
 * color of it in the shop: UNAVAILABLE; the cart already holds all of it the shop allows: ALREADY_AT_MAX; one row per
 * product. One run of CartBulkAdd, «خرید دوباره»'s own. The list is not changed, and it never fails for want of stock.
 *
 * Returns the cart's change and what went in or was skipped: like ReorderUc, a documented exception to «a command
 * returns only ids».
 */
class AddWishlistToCartUc(
    private val transactionManager: TransactionManager,
    private val wishlistRepository: WishlistRepository,
    private val wishlistCards: WishlistCards,
    private val cartBulkAdd: CartBulkAdd,
    private val clock: Clock,
) : CommandUseCase<AddWishlistToCartUc.Cmd, AddWishlistToCartUc.ToCart, AddWishlistToCartUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [productIds] the body's, if any (none or empty: the whole list). */
    data class Cmd(val userId: UserId, val productIds: List<Long?>?)

    data class ToCart(val change: CartChange, val addedCount: Int, val skipped: List<Skipped>)

    data class Skipped(val productId: Long, val name: String, val reason: Reordered.Reason)

    /** One product of the list and its colors to try, the saved one first. */
    private class Wanted(val productId: Long, val name: String, val colors: List<VariantId>)

    override suspend fun execute(command: Cmd): Result<ToCart, Failure> {
        logger.info("AddWishlistToCartUc.execute userId=${command.userId.value} productIds=${command.productIds?.size}")
        val only = when (val checked = idsOf(command.productIds)) {
            is Result.Success -> checked.value
            is Result.Failure -> return Result.failure(checked.failure)
        }

        val wanted = transactionManager.inTransaction {
            val seen = wishlistCards.of(wishlistRepository.find(command.userId), clock.instant(), isWishlisted = null)
            Result.success(
                seen.filter { only == null || it.entry.productId.value in only }.map { item ->
                    val saved = item.qm.colors.filter { it.colorKey == item.entry.preferredColorKey }
                    Wanted(item.qm.productId.value, item.qm.name.value, (saved + (item.qm.colors - saved.toSet())).map { it.variantId })
                },
            )
        }.getOrNull().orEmpty()

        val added = cartBulkAdd.add(CartCaller.Customer(command.userId), alsoOffersOf = wanted.flatMap { it.colors }) { context ->
            val lines = mutableListOf<Pair<VariantId, CartQuantity>>()
            val skipped = mutableListOf<Reordered.Skipped>()
            wanted.forEach { product ->
                val offers = product.colors.mapNotNull { context.offers[it] }
                val chosen = offers.firstOrNull { it.isPurchasable }
                when {
                    chosen != null -> lines += chosen.variantId to ONE
                    offers.none { it.inShop } -> skipped += Reordered.Skipped(product.colors.first(), Reordered.Reason.UNAVAILABLE)
                    else -> skipped += Reordered.Skipped(product.colors.first(), Reordered.Reason.OUT_OF_STOCK)
                }
            }
            CartBulkAdd.Picked(lines, skipped)
        }

        val byColor = wanted.flatMap { product -> product.colors.map { it to product } }.toMap()
        val skipped = added.skipped.map { byColor.getValue(it.variantId).let { p -> Skipped(p.productId, p.name, it.reason) } }
        logger.info("AddWishlistToCartUc: ${added.addedQuantity} bags into the cart of user ${command.userId.value}, skipped ${skipped.map { it.productId to it.reason }}")
        return Result.success(ToCart(added.change, added.addedQuantity, skipped))
    }

    /** The body's ids: at most 200, each a product id; null when none was sent (the whole list). */
    private fun idsOf(raw: List<Long?>?): Result<Set<Long>?, Failure> {
        if (raw.isNullOrEmpty()) return Result.success(null)
        if (raw.size > WishlistPolicy.MAX_ITEMS) return Result.failure(Failure.InvalidInput(listOf(FieldFailure("productIds", TooManyItems(WishlistPolicy.MAX_ITEMS)))))
        val problems = raw.withIndex().filter { (_, id) -> id == null || id <= 0 }.map { (i, _) -> FieldFailure("productIds.$i", Problem.ProductIdInvalid) }
        return if (problems.isEmpty()) Result.success(raw.filterNotNull().toSet()) else Result.failure(Failure.InvalidInput(problems))
    }

    private companion object {
        val ONE = CartQuantity.constructFromPersisted(1)
    }

    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object ProductIdInvalid : Problem("PRODUCT_ID_INVALID")
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
