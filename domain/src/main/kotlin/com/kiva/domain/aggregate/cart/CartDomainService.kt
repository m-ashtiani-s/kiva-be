package com.kiva.domain.aggregate.cart

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.bind
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.entity.CartLine
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.Reordered
import com.kiva.domain.aggregate.cart.model.ReservationStanding
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.cart.repository.CartIdGenerator
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * The cart commands, each the way the business wants it done, so no use case can get the order wrong:
 * - a change that depends on the shop (add, change a line, choose, reserve) first brings the cart up to date
 *   ([Cart.sync]), so its rules are checked against today's stock and prices and the shopper hears what changed;
 * - a change that only takes something away (remove, clear) comes first, so the shopper isn't told about a line they
 *   just threw out.
 *
 * Round 2: every command takes the customer's [ReservationStanding] (a guest's is NONE), so the switch is never on
 * while they hold a reservation, and an order from the cart that joins it ships only with the group's method.
 *
 * It also hands new carts and lines their random ids. It never saves and never opens a transaction; the use case
 * locks the cart, calls this, and saves, all in its one transaction. The issues are on the cart ([Cart.issues]).
 */
class CartDomainService(private val cartIdGenerator: CartIdGenerator) {

    private val logger = getLogger()

    /** A customer's first cart, shipping by the shop's default method. */
    fun newUserCart(userId: UserId, settings: ShopSettings, now: Instant): Cart {
        logger.info("newUserCart userId=${userId.value}")
        return Cart.new(cartIdGenerator.nextCartPublicId(), CartOwner.User(userId), settings.defaultShippingMethod, now)
    }

    /** A guest's new cart, and its token: shown to the browser once, only its hash is kept. */
    fun newGuestCart(settings: ShopSettings, now: Instant): NewGuestCart {
        val token = cartIdGenerator.nextGuestToken()
        val cart = Cart.new(cartIdGenerator.nextCartPublicId(), CartOwner.Guest(token.hash()), settings.defaultShippingMethod, now)
        logger.info("newGuestCart cart=${cart.publicId.value}")
        return NewGuestCart(cart, token)
    }

    /** Looking at the cart (`GET /cart`): brings it up to date and records it as seen. */
    fun openCart(cart: Cart, offers: Map<VariantId, PurchaseOffer>, settings: ShopSettings, now: Instant, standing: ReservationStanding = ReservationStanding.NONE) {
        logger.info("openCart cart=${cart.publicId.value}")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
    }

    fun addItem(
        cart: Cart,
        variantId: VariantId,
        quantity: CartQuantity,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Result<CartItemId, Cart.Failure> {
        logger.info("addItem cart=${cart.publicId.value} variantId=${variantId.value} quantity=${quantity.value}")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        return cart.add(cartIdGenerator.nextCartItemId(), variantId, quantity, offers, now)
    }

    /**
     * «خرید دوباره» (`POST /me/orders/{orderCode}/reorder`): the colors of an earlier order, each with its quantity, into
     * the cart as far as the shop allows now, each the way adding one does (a color already there adds to its line; more
     * than fits is cut, with QUANTITY_REDUCED). A color that can't be added at all is skipped and told why; one that adds
     * nothing because the cart already holds all it may is ALREADY_AT_MAX. It never fails: an order whose bags are all
     * gone adds nothing. The cart is brought up to date first, like any change that depends on the shop.
     */
    fun reorder(
        cart: Cart,
        lines: List<Pair<VariantId, CartQuantity>>,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Reordered {
        logger.info("reorder cart=${cart.publicId.value} lines=${lines.map { (variantId, quantity) -> "${variantId.value}x${quantity.value}" }}")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        var added = 0
        val skipped = mutableListOf<Reordered.Skipped>()
        lines.forEach { (variantId, quantity) ->
            val before = cart.lineOf(variantId)?.quantity?.value ?: 0
            when (val result = cart.add(cartIdGenerator.nextCartItemId(), variantId, quantity, offers, now)) {
                is Result.Success -> {
                    val after = cart.lineOf(variantId)?.quantity?.value ?: 0
                    if (after > before) added += after - before else skipped += Reordered.Skipped(variantId, Reordered.Reason.ALREADY_AT_MAX)
                }
                is Result.Failure -> skipped += Reordered.Skipped(variantId, reasonOf(result.failure))
            }
        }
        return Reordered(added, skipped)
    }

    private fun reasonOf(failure: Cart.Failure): Reordered.Reason = when (failure) {
        Cart.Failure.OutOfStock -> Reordered.Reason.OUT_OF_STOCK
        Cart.Failure.VariantNotFound, Cart.Failure.ProductUnavailable -> Reordered.Reason.UNAVAILABLE
        is Cart.Failure.LimitTakenByOtherColors -> Reordered.Reason.ALREADY_AT_MAX
        else -> error("Adding a color can't fail with ${failure.code}")
    }

    /**
     * Changes a line's color, its quantity, or both (`PATCH /cart/items/{itemId}`). With both, the color goes first,
     * and the quantity is then set on the line that remains (a merge may leave another line's id).
     */
    fun changeItem(
        cart: Cart,
        itemId: CartItemId,
        quantity: CartQuantity?,
        variantId: VariantId?,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Result<CartItemId, Cart.Failure> {
        require(quantity != null || variantId != null) { "A change needs a quantity or a color" }
        logger.info("changeItem cart=${cart.publicId.value} itemId=${itemId.value} quantity=${quantity?.value} variantId=${variantId?.value}")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        val afterColor = if (variantId != null) cart.changeColor(itemId, variantId, offers, now) else Result.success(itemId)
        return if (quantity == null) afterColor else afterColor.bind { cart.changeQuantity(it, quantity, offers, now) }
    }

    /** Takes a line out, then brings the rest up to date. Returns the line, for «برگردون». */
    fun removeItem(
        cart: Cart,
        itemId: CartItemId,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Result<CartLine, Cart.Failure> {
        logger.info("removeItem cart=${cart.publicId.value} itemId=${itemId.value}")
        return cart.remove(itemId, now).also { if (it.isSuccess) cart.sync(offers, settings, now, standing.hasActiveReservation) }
    }

    /** Empties the cart, then brings what is left (its shipping method) up to date. */
    fun clearCart(cart: Cart, offers: Map<VariantId, PurchaseOffer>, settings: ShopSettings, now: Instant, standing: ReservationStanding = ReservationStanding.NONE) {
        logger.info("clearCart cart=${cart.publicId.value}")
        cart.clear(now)
        cart.sync(offers, settings, now, standing.hasActiveReservation)
    }

    fun chooseShippingMethod(
        cart: Cart,
        method: ShippingMethodCode,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Result<Unit, Cart.Failure> {
        logger.info("chooseShippingMethod cart=${cart.publicId.value} method=$method")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        return cart.chooseShippingMethod(method, settings, now, standing.consolidation)
    }

    fun setReservation(
        cart: Cart,
        enabled: Boolean,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ): Result<Unit, Cart.Failure> {
        logger.info("setReservation cart=${cart.publicId.value} enabled=$enabled")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        return cart.setReservation(enabled, offers, settings, now, standing.hasActiveReservation)
    }

    /**
     * After a login: both carts are brought up to date, then the guest's goes into the customer's ([Cart.absorb]).
     * Without a guest cart (no token, or one that reaches none) it is a look at the customer's cart, as [openCart]: the
     * login's answer shows the cart, so what changed since the last look is told there, once.
     * [offers] must cover the lines of both. The use case deletes the guest cart.
     */
    fun mergeGuestCart(
        cart: Cart,
        guest: Cart?,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        standing: ReservationStanding = ReservationStanding.NONE,
    ) {
        logger.info("mergeGuestCart cart=${cart.publicId.value} guestCart=${guest?.publicId?.value} guestLines=${guest?.lines?.size}")
        cart.sync(offers, settings, now, standing.hasActiveReservation)
        if (guest == null) return
        guest.sync(offers, settings, now, standing.hasActiveReservation)
        cart.absorb(guest, offers, settings, now, standing.hasActiveReservation)
    }

    /** A guest's new cart and the token that reaches it. [token]'s toString hides it. */
    data class NewGuestCart(val cart: Cart, val token: CartToken)
}
