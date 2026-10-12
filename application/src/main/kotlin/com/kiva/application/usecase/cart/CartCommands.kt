package com.kiva.application.usecase.cart

import com.kiva.application.dto.CartChange
import com.kiva.application.dto.CartIssueDto
import com.kiva.application.dto.RemovedItemDto
import com.kiva.application.repository.OfferQm
import com.kiva.application.repository.OfferQmRepository
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock
import java.time.Instant

/**
 * The one way every cart command runs, in one transaction (roadmap section 6). Shared orchestration of the cart's use
 * cases (Rule 15); the business decisions stay in CartDomainService and the Cart.
 *
 * 1. finds the caller's cart and locks its row: a customer's, made on their first request (two first requests at once
 *    make one cart); a guest's by the hash of their token, or a new one with a new token when the token is missing,
 *    malformed or unknown. A Bearer token always wins over a cart token: a customer never reaches a guest cart this way;
 * 2. reads the shop's settings and the offers of the cart's colors, and of [run]'s `alsoOffersOf`, in one query;
 * 3. lets the use case make its change through CartDomainService;
 * 4. saves, and describes what happened ([CartChange]).
 *
 * A failed change rolls everything back, a new guest cart included, so its token is never handed out.
 */
class CartCommands(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val cartDomainService: CartDomainService,
    private val offerQmRepository: OfferQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val clock: Clock,
) {
    /** What a command's own change works with. [offers] cover every line of [cart] and the colors asked for. */
    class Context(val cart: Cart, val offers: Map<VariantId, PurchaseOffer>, val settings: ShopSettings, val now: Instant)

    /** What the change says it did, besides the issues the cart collected. */
    data class Done(val affectedItemId: CartItemId? = null, val addedQuantity: Int? = null, val removedLine: CartLine? = null)

    suspend fun <F : ResultFailure> run(
        caller: CartCaller,
        alsoOffersOf: Collection<VariantId> = emptyList(),
        change: (Context) -> Result<Done, F>,
    ): Result<CartChange, F> = transactionManager.inTransaction {
        val settings = shopSettingsRepository.get()
        val now = clock.instant()
        val (cart, newToken) = lockOrCreate(caller, settings, now)
        val offers = offerQmRepository.findOffers(cart.lines.map { it.variantId } + alsoOffersOf)

        val done = change(Context(cart, offers.mapValues { it.value.offer }, settings, now)).unwrapOr { return@inTransaction Result.failure(it) }
        cartRepository.save(cart)

        Result.success(changeOf(cart, newToken, offers, done))
    }

    /**
     * The run of a merge after a login (MergeGuestCartUc), in one transaction: the customer's cart is locked (made, if
     * they have none), then the guest cart of [guestToken] (section 11's order: the customer's first, so two merges
     * never wait on each other in a circle), then the offers of both carts' lines are read in one query. [change] gets
     * the guest cart, or null when the token reaches none: missing, malformed, unknown, or merged a moment ago by
     * another request, which deleted it while this one waited on its lock. A token never reaches a customer's cart.
     *
     * The guest cart is deleted before the customer's is saved: its lines move over keeping their ids.
     */
    suspend fun runMerge(userId: UserId, guestToken: String?, change: (Context, guest: Cart?) -> Unit): Result<CartChange, Nothing> =
        transactionManager.inTransaction {
            val settings = shopSettingsRepository.get()
            val now = clock.instant()
            val (cart, _) = lockOrCreate(CartCaller.Customer(userId), settings, now)
            val guest = guestToken?.let { CartToken.makeNew(it).getOrNull() }?.let { cartRepository.lockForGuest(it.hash()) }
            val offers = offerQmRepository.findOffers((cart.lines + guest?.lines.orEmpty()).map { it.variantId })

            change(Context(cart, offers.mapValues { it.value.offer }, settings, now), guest)
            guest?.let(cartRepository::delete)
            cartRepository.save(cart)

            Result.success(changeOf(cart, newToken = null, offers, Done()))
        }

    private fun changeOf(cart: Cart, newToken: CartToken?, offers: Map<VariantId, OfferQm>, done: Done): CartChange =
        CartChange(
            cartId = cart.publicId,
            newToken = newToken,
            issues = cart.issues.map { issue ->
                val names = issue.variantId?.let { offers[it] }
                CartIssueDto(issue.code, issue.itemId?.value, names?.product?.name, names?.color?.name, issue.previousValue, issue.currentValue)
            },
            affectedItemId = done.affectedItemId,
            addedQuantity = done.addedQuantity,
            removedItem = done.removedLine?.let { removedItemOf(it, offers) },
        )

    private fun lockOrCreate(caller: CartCaller, settings: ShopSettings, now: Instant): Pair<Cart, CartToken?> = when (caller) {
        is CartCaller.Customer -> {
            val cart = cartRepository.lockForUser(caller.userId) ?: run {
                cartRepository.insertForUserIfAbsent(cartDomainService.newUserCart(caller.userId, settings, now))
                cartRepository.lockForUser(caller.userId) ?: error("The cart of user ${caller.userId.value} was stored, yet it isn't there")
            }
            cart to null
        }
        is CartCaller.Guest -> {
            val known = caller.token?.let { CartToken.makeNew(it).getOrNull() }?.let { cartRepository.lockForGuest(it.hash()) }
            if (known != null) {
                known to null
            } else {
                val new = cartDomainService.newGuestCart(settings, now)
                cartRepository.insertGuest(new.cart)
                new.cart to new.token
            }
        }
    }

    // The line's color was in the cart, so its offer was read: the read model keeps every color ever made.
    private fun removedItemOf(line: CartLine, offers: Map<VariantId, OfferQm>): RemovedItemDto {
        val offer = offers[line.variantId] ?: error("No offer for variant ${line.variantId.value} of a cart line")
        return RemovedItemDto(line.variantId.value, line.quantity.value, offer.product.name, offer.color.name)
    }
}
