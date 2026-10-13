package com.kiva.domain.aggregate.cart.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.cart.model.CartIssue
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.cart.model.policy.CartLimitsPolicy
import com.kiva.domain.aggregate.cart.model.policy.ReservationPolicy
import com.kiva.domain.aggregate.cart.model.valueobject.CartItemId
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.model.valueobject.CartQuantity
import com.kiva.domain.aggregate.cart.model.valueobject.ReservationUnavailableReason
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeText
import com.kiva.domain.aggregate.order.model.policy.ShippingPolicy
import com.kiva.domain.aggregate.order.model.valueobject.ShippingConsolidation
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * A shopper's cart: a customer's (one each) or a guest's. The rules it keeps:
 * - one line per color; adding a color again adds to its line;
 * - a line holds at most what is left of its color, and all colors of a product together at most the product's
 *   limit per order (CartLimitsPolicy); when that limit has to be cut, the older lines keep their share;
 * - a line that can't be bought any more (none left, or out of the shop) stays, so the shopper sees what happened,
 *   but counts in no money and blocks placing an order until it is removed;
 * - the «رزرو ۴ روزه» switch stays on only while a reservation is possible;
 * - a discount code (round 2) stays only while it applies: the use case checks it with DiscountDomainService after every
 *   change, and one that no longer applies goes, with DISCOUNT_REMOVED.
 *
 * It stores no price and no stock: every operation gets the catalog's current [PurchaseOffer]s, keyed by color. They
 * must cover every line of the cart and the color asked for.
 *
 * What changed since the shopper last looked is collected in [issues] while the cart is in use, like events an
 * aggregate has not saved yet: the use case hands them to the response, and they are never stored.
 *
 * Its identity is [publicId].
 */
class Cart private constructor(
    val publicId: CartPublicId,
    val owner: CartOwner,
    lines: List<CartLine>,
    shippingMethod: ShippingMethodCode,
    reserve: Boolean,
    val createdOn: Instant,
    updatedOn: Instant,
    discountCode: DiscountCodeText?,
) {
    private val entries: MutableList<CartLine> = lines.sortedWith(OLDEST_FIRST).toMutableList()
    private val raised: MutableList<CartIssue> = mutableListOf()

    /** The method the shopper chose (a new cart starts with the shop's default). */
    var shippingMethod: ShippingMethodCode = shippingMethod
        private set

    /** The «رزرو ۴ روزه» switch. */
    var reserve: Boolean = reserve
        private set

    /** The last time the cart was used; old guest carts are cleaned up by it. */
    var updatedOn: Instant = updatedOn
        private set

    /** The discount code the shopper applied (round 2), checked to apply when it was applied and after every change. */
    var discountCode: DiscountCodeText? = discountCode
        private set

    // Only this class writes lines, so a broken cart is a bug.
    init {
        require(entries.distinctBy { it.variantId }.size == entries.size) { "Cart ${publicId.value} holds a color twice" }
    }

    /** Oldest first. */
    val lines: List<CartLine> get() = entries.toList()

    /** What changed for the shopper since the cart was loaded, in the order it happened. */
    val issues: List<CartIssue> get() = raised.toList()

    val isGuest: Boolean get() = owner is CartOwner.Guest

    fun find(itemId: CartItemId): CartLine? = entries.firstOrNull { it.itemId == itemId }

    fun lineOf(variantId: VariantId): CartLine? = entries.firstOrNull { it.variantId == variantId }

    /** The most [line] may hold now (the contract's `CartItem.maxQuantity`); 0 when it can't be bought. */
    fun maxQuantityOf(line: CartLine, offers: Map<VariantId, PurchaseOffer>): Int {
        val offer = offers[line.variantId] ?: return 0
        return CartLimitsPolicy.maxQuantity(offer, othersOfProduct(offer.productId, offers, except = setOf(line.itemId)))
    }

    /**
     * Why the reservation switch can't be turned on, in the contract's order; null when it can.
     *
     * @param hasActiveReservation round 2: the customer already has a reservation holding. Unknown for a guest.
     */
    fun reservationUnavailableReason(
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        hasActiveReservation: Boolean = false,
    ): ReservationUnavailableReason? =
        ReservationPolicy.unavailableReason(settings, entries.mapNotNull { offers[it.variantId] }, hasActiveReservation)

    // ============ looking at the cart ============

    /**
     * Brings the cart up to date with the shop, and records it as seen. Every change is raised once as an issue:
     * - quantities are cut to what is left and to the product's limit, oldest line first; a line left with no room under
     *   the limit goes (QUANTITY_REDUCED to 0);
     * - a line that could be bought and can't any more: OUT_OF_STOCK, or VARIANT_UNAVAILABLE when it left the shop;
     * - a new price on a line that can be bought: PRICE_INCREASED or PRICE_DECREASED. A line that can't be bought keeps
     *   the price last seen, so a change shows when it comes back;
     * - a shipping method no longer offered moves to the shop's default (SHIPPING_METHOD_CHANGED);
     * - the reservation switch goes off, silently, when a reservation is no longer possible.
     */
    fun sync(offers: Map<VariantId, PurchaseOffer>, settings: ShopSettings, now: Instant, hasActiveReservation: Boolean = false) {
        fitToLimits(offers)
        entries.replaceAll { see(it, offers[it.variantId]) }

        val offered = ShippingPolicy.offeredMethods(settings, consolidation = null)
        val method = ShippingPolicy.methodFor(offered, shippingMethod, settings.defaultShippingMethod)
        if (method != null && method != shippingMethod) {
            shippingMethod = method
            raised += CartIssue.shippingMethodChanged()
        }
        dropReservationIfUnavailable(offers, settings, hasActiveReservation)
        updatedOn = now
    }

    // ============ lines ============

    /**
     * Adds [quantity] of a color; a color already in the cart adds to its line. More than the line may hold is cut to
     * that, with QUANTITY_REDUCED (the contract's «حداکثر موجودی: n عدد»). [itemId] is used only for a new line.
     */
    fun add(
        itemId: CartItemId,
        variantId: VariantId,
        quantity: CartQuantity,
        offers: Map<VariantId, PurchaseOffer>,
        now: Instant,
    ): Result<CartItemId, Failure> {
        val offer = offers[variantId] ?: return Result.failure(Failure.VariantNotFound)
        if (!offer.inShop) return Result.failure(Failure.ProductUnavailable)
        if (offer.available == 0) return Result.failure(Failure.OutOfStock)

        val existing = lineOf(variantId)
        val maxQuantity = CartLimitsPolicy.maxQuantity(offer, othersOfProduct(offer.productId, offers, setOfNotNull(existing?.itemId)))
        // Some is left, so nothing fits only because the product's other colors in the cart took its whole limit.
        if (maxQuantity == 0) return Result.failure(Failure.LimitTakenByOtherColors(offer.maxPerOrder.value))

        val asked = (existing?.quantity?.value ?: 0) + quantity.value
        val kept = minOf(asked, maxQuantity)
        val line = (existing ?: CartLine(itemId, variantId, CartQuantity(kept), offer.priceTag.price, true, now))
            .copy(quantity = CartQuantity(kept), seenUnitPrice = offer.priceTag.price, seenPurchasable = true)
        put(line)
        if (kept < asked) raised += CartIssue.quantityReduced(line.itemId, variantId, asked, kept)
        updatedOn = now
        return Result.success(line.itemId)
    }

    /**
     * Sets a line's quantity. Unlike adding, too many is refused, not cut: more than is left is QUANTITY_EXCEEDS_STOCK,
     * more than the product's limit allows next to its other colors is QUANTITY_EXCEEDS_LIMIT. Both tell the line's
     * maxQuantity, never the exact stock.
     */
    fun changeQuantity(
        itemId: CartItemId,
        quantity: CartQuantity,
        offers: Map<VariantId, PurchaseOffer>,
        now: Instant,
    ): Result<CartItemId, Failure> {
        val line = find(itemId) ?: return Result.failure(Failure.CartItemNotFound)
        if (quantity == line.quantity) return Result.success(itemId)
        val offer = offers[line.variantId]
        if (offer == null || !offer.inShop) return Result.failure(Failure.ProductUnavailable)
        if (offer.available == 0) return Result.failure(Failure.OutOfStock)

        val maxQuantity = CartLimitsPolicy.maxQuantity(offer, othersOfProduct(offer.productId, offers, setOf(itemId)))
        if (quantity.value > offer.available) return Result.failure(Failure.QuantityExceedsStock(maxQuantity))
        if (quantity.value > maxQuantity) return Result.failure(Failure.QuantityExceedsLimit(maxQuantity))

        put(line.copy(quantity = quantity))
        updatedOn = now
        return Result.success(itemId)
    }

    /**
     * Moves a line to another color of the same product. When that color already has a line, the two become one (the
     * quantities added up) and that line's id is what remains. More than the new color may hold is cut, with
     * QUANTITY_REDUCED. The line keeps its place (addedOn) and is seen at the new color's price.
     */
    fun changeColor(
        itemId: CartItemId,
        variantId: VariantId,
        offers: Map<VariantId, PurchaseOffer>,
        now: Instant,
    ): Result<CartItemId, Failure> {
        val line = find(itemId) ?: return Result.failure(Failure.CartItemNotFound)
        if (variantId == line.variantId) return Result.success(itemId)
        val current = offers[line.variantId]
        val target = offers[variantId]
        if (current == null || target == null || target.productId != current.productId) return Result.failure(Failure.NotAColorOfThisProduct)
        if (!target.inShop) return Result.failure(Failure.ProductUnavailable)
        if (target.available == 0) return Result.failure(Failure.OutOfStock)

        val existing = lineOf(variantId)
        val others = othersOfProduct(target.productId, offers, setOfNotNull(itemId, existing?.itemId))
        val maxQuantity = CartLimitsPolicy.maxQuantity(target, others)
        if (maxQuantity == 0) return Result.failure(Failure.LimitTakenByOtherColors(target.maxPerOrder.value))

        val asked = line.quantity.value + (existing?.quantity?.value ?: 0)
        val kept = minOf(asked, maxQuantity)
        val moved = (existing ?: line.copy(variantId = variantId))
            .copy(quantity = CartQuantity(kept), seenUnitPrice = target.priceTag.price, seenPurchasable = true)
        if (existing != null) entries.remove(line)
        put(moved)
        if (kept < asked) raised += CartIssue.quantityReduced(moved.itemId, variantId, asked, kept)
        updatedOn = now
        return Result.success(moved.itemId)
    }

    /** Takes a line out. Returns it, so the shopper can be offered «برگردون» (its color and quantity). */
    fun remove(itemId: CartItemId, now: Instant): Result<CartLine, Failure> {
        val line = find(itemId) ?: return Result.failure(Failure.CartItemNotFound)
        entries.remove(line)
        updatedOn = now
        return Result.success(line)
    }

    /**
     * Empties the cart: the lines go, the reservation switch goes off and the discount code goes (an order took it, or
     * there is nothing left for it). The shipping method stays.
     */
    fun clear(now: Instant) {
        entries.clear()
        reserve = false
        discountCode = null
        updatedOn = now
    }

    // ============ choices ============

    /**
     * Chooses how the order ships. Only an offered method can be chosen; while the order joins a reservation
     * ([consolidation], round 2), only the group's.
     */
    fun chooseShippingMethod(
        method: ShippingMethodCode,
        settings: ShopSettings,
        now: Instant,
        consolidation: ShippingConsolidation? = null,
    ): Result<Unit, Failure> {
        if (method !in ShippingPolicy.offeredMethods(settings, consolidation)) return Result.failure(Failure.ShippingMethodUnavailable)
        shippingMethod = method
        updatedOn = now
        return Result.success(Unit)
    }

    /** Turns the reservation switch on, only while a reservation is possible; off, always. */
    fun setReservation(
        enabled: Boolean,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        hasActiveReservation: Boolean = false,
    ): Result<Unit, Failure> {
        if (enabled) {
            reservationUnavailableReason(offers, settings, hasActiveReservation)?.let { return Result.failure(Failure.ReservationUnavailable(it)) }
        }
        reserve = enabled
        updatedOn = now
        return Result.success(Unit)
    }

    // ============ the discount code (round 2) ============

    /** Applies [code], in place of any other: DiscountDomainService found it applies to this cart now. */
    fun applyDiscountCode(code: DiscountCodeText, now: Instant) {
        discountCode = code
        updatedOn = now
    }

    /** The shopper took the code off. Without one, nothing changes but the time. */
    fun removeDiscountCode(now: Instant) {
        discountCode = null
        updatedOn = now
    }

    /** The code no longer applies (DiscountDomainService said why): it goes, and the shopper is told once. */
    fun dropDiscountCode(now: Instant) {
        val code = discountCode ?: return
        discountCode = null
        raised += CartIssue.discountRemoved(code)
        updatedOn = now
    }

    // ============ after login ============

    /**
     * Takes in a guest's cart after its owner logged in; the guest cart is deleted afterwards. A color in both carts
     * becomes one line (the customer's) with both quantities; any other guest line moves over as it is, keeping its
     * id, so the shopper's page doesn't lose track of it. Then the limits are applied to the whole cart, oldest line
     * first, as in [sync]. The guest's shipping method and reservation switch win, since the shopper chose them just
     * now, but only when the guest cart holds something: an empty one was never used (every visit makes one) and has
     * only the shop's default, so it leaves the customer's choices as they are. The discount code is the customer's when
     * they had one, else the guest's (contract); the use case then checks it for the customer. The guest cart's own
     * issues come along.
     */
    fun absorb(
        guest: Cart,
        offers: Map<VariantId, PurchaseOffer>,
        settings: ShopSettings,
        now: Instant,
        hasActiveReservation: Boolean = false,
    ) {
        require(owner is CartOwner.User && guest.owner is CartOwner.Guest) { "Only a guest cart goes into a customer's cart" }
        raised += guest.issues

        val asked = mutableMapOf<CartItemId, Int>()
        guest.entries.forEach { guestLine ->
            val mine = lineOf(guestLine.variantId)
            if (mine == null) {
                entries += guestLine
            } else {
                asked[mine.itemId] = (asked[mine.itemId] ?: mine.quantity.value) + guestLine.quantity.value
            }
        }
        entries.sortWith(OLDEST_FIRST)
        if (guest.entries.isNotEmpty()) {
            shippingMethod = guest.shippingMethod
            reserve = guest.reserve
        }
        if (discountCode == null) discountCode = guest.discountCode
        fitToLimits(offers, asked)
        dropReservationIfUnavailable(offers, settings, hasActiveReservation)
        updatedOn = now
    }

    // ============ inside ============

    /**
     * Cuts each line to what its color's stock and its product's limit allow, oldest first, so the older lines keep
     * their share. A line that can't be bought is held to the limit only: it still takes its share of it. A line left
     * with no room goes. [asked] holds quantities wanted for a line that it doesn't hold yet (a merge).
     */
    private fun fitToLimits(offers: Map<VariantId, PurchaseOffer>, asked: Map<CartItemId, Int> = emptyMap()) {
        val used = mutableMapOf<ProductId, Int>()
        for (line in entries.toList()) {
            val wanted = asked[line.itemId] ?: line.quantity.value
            val offer = offers[line.variantId]
            val allowed = when {
                offer == null -> CartQuantity.MAX
                offer.isPurchasable -> CartLimitsPolicy.maxQuantity(offer, used[offer.productId] ?: 0)
                else -> CartLimitsPolicy.roomUnderLimit(offer, used[offer.productId] ?: 0)
            }
            if (allowed == 0) {
                entries.remove(line)
                raised += CartIssue.quantityReduced(line.itemId, line.variantId, wanted, 0)
                continue
            }
            val kept = minOf(wanted, allowed)
            if (kept < wanted) raised += CartIssue.quantityReduced(line.itemId, line.variantId, wanted, kept)
            if (kept != line.quantity.value) put(line.copy(quantity = CartQuantity(kept)))
            if (offer != null) used[offer.productId] = (used[offer.productId] ?: 0) + kept
        }
    }

    /** The line as the shopper sees it now, raising what changed since they last did. */
    private fun see(line: CartLine, offer: PurchaseOffer?): CartLine {
        if (offer == null || !offer.isPurchasable) {
            if (line.seenPurchasable) raised += if (offer != null && offer.inShop) CartIssue.outOfStock(line) else CartIssue.variantUnavailable(line)
            return line.copy(seenPurchasable = false)
        }
        val price = offer.priceTag.price
        if (price != line.seenUnitPrice) raised += CartIssue.priceChanged(line, line.seenUnitPrice.toman, price.toman)
        return line.copy(seenUnitPrice = price, seenPurchasable = true)
    }

    private fun dropReservationIfUnavailable(offers: Map<VariantId, PurchaseOffer>, settings: ShopSettings, hasActiveReservation: Boolean) {
        if (reserve && reservationUnavailableReason(offers, settings, hasActiveReservation) != null) reserve = false
    }

    /** What the product's lines other than [except] hold, whether they can be bought or not. */
    private fun othersOfProduct(productId: ProductId, offers: Map<VariantId, PurchaseOffer>, except: Set<CartItemId>): Int =
        entries
            .filter { it.itemId !in except && offers[it.variantId]?.productId == productId }
            .sumOf { it.quantity.value }

    /** Puts [line] in place of the line with its id, or adds it; the lines stay oldest first. */
    private fun put(line: CartLine) {
        val index = entries.indexOf(line)
        if (index >= 0) entries[index] = line else entries += line
        entries.sortWith(OLDEST_FIRST)
    }

    override fun toString(): String = "Cart(publicId=${publicId.value}, owner=$owner, lines=${entries.size})"

    companion object {
        private val OLDEST_FIRST = compareBy<CartLine>({ it.addedOn }, { it.itemId.value })

        /** An empty cart, shipping by [shippingMethod] (the shop's default). */
        fun new(publicId: CartPublicId, owner: CartOwner, shippingMethod: ShippingMethodCode, now: Instant): Cart =
            Cart(publicId, owner, emptyList(), shippingMethod, reserve = false, createdOn = now, updatedOn = now, discountCode = null)

        fun constructFromPersisted(
            publicId: CartPublicId,
            owner: CartOwner,
            lines: List<CartLine>,
            shippingMethod: ShippingMethodCode,
            reserve: Boolean,
            createdOn: Instant,
            updatedOn: Instant,
            discountCode: DiscountCodeText? = null,
        ): Cart = Cart(publicId, owner, lines, shippingMethod, reserve, createdOn, updatedOn, discountCode)
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data object VariantNotFound : Failure("VARIANT_NOT_FOUND", FailureKind.NOT_FOUND)

        /** The new color of a line must be a color of the same product: a wrong input, on `variantId`. */
        data object NotAColorOfThisProduct : Failure("VARIANT_ID_INVALID", FailureKind.INVALID, field = "variantId")

        data object ProductUnavailable : Failure("PRODUCT_UNAVAILABLE", FailureKind.BUSINESS_RULE)

        data object OutOfStock : Failure("OUT_OF_STOCK", FailureKind.BUSINESS_RULE)

        /** Some is left, but the product's other colors in the cart hold its whole limit. */
        data class LimitTakenByOtherColors(val maxPerOrder: Int) :
            Failure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, meta = mapOf("maxPerOrder" to maxPerOrder))

        data class QuantityExceedsLimit(val maxQuantity: Int) :
            Failure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, meta = mapOf("maxQuantity" to maxQuantity))

        /** [availableQuantity] is the line's maxQuantity, not the stock: the exact stock never leaves the shop. */
        data class QuantityExceedsStock(val availableQuantity: Int) :
            Failure("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, meta = mapOf("availableQuantity" to availableQuantity))

        /** Not in this cart, whether it belongs to another cart or to none: the same 404 either way. */
        data object CartItemNotFound : Failure("CART_ITEM_NOT_FOUND", FailureKind.NOT_FOUND)

        data object ShippingMethodUnavailable : Failure("SHIPPING_METHOD_UNAVAILABLE", FailureKind.BUSINESS_RULE)

        data class ReservationUnavailable(val reason: ReservationUnavailableReason) :
            Failure("RESERVATION_UNAVAILABLE", FailureKind.BUSINESS_RULE, meta = mapOf("reason" to reason.name))
    }
}
