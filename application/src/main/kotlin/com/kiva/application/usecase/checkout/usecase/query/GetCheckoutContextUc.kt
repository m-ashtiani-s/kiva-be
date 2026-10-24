package com.kiva.application.usecase.checkout.usecase.query

import com.kiva.application.usecase.cart.withHearts
import com.kiva.application.usecase.wishlist.WishlistHearts
import com.kiva.application.dto.CheckoutContextDto
import com.kiva.application.port.PaymentGateways
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.usecase.address.addressDtosOf
import com.kiva.application.usecase.address.addressIdOf
import com.kiva.application.usecase.address.defaultFirst
import com.kiva.application.usecase.cart.cartDtoOf
import com.kiva.application.usecase.discount.Discounts
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock

/**
 * Everything the checkout page needs in one answer (`GET /checkout`): the cart exactly as `GET /cart` shows it, the
 * customer's addresses and the one checkout starts with, the messenger and number the photo before shipping goes to,
 * and the payment gateways.
 *
 * The route first opens the customer's cart with OpenCartUc, so it is brought up to date with the shop and its issues
 * are told once, as on the cart page; then it asks this query with the id that command returned.
 *
 * Refused, in the order placing the order (`POST /orders`) checks the same things:
 * 1. a cart without a single line is CART_EMPTY. A line that can't be bought is still a line: the page shows it, and
 *    the order answers it with PRODUCT_UNAVAILABLE or OUT_OF_STOCK;
 * 2. an `addressId` that isn't one of the customer's own addresses is a 400 on `addressId`: someone else's, one that
 *    is gone and a malformed one alike, so nobody learns another customer's address is there.
 */
class GetCheckoutContextUc(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val offerQmRepository: OfferQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val addressBookRepository: AddressBookRepository,
    private val geoRepository: GeoRepository,
    private val userRepository: UserRepository,
    private val paymentGateways: PaymentGateways,
    private val reservations: Reservations,
    private val discounts: Discounts,
    private val clock: Clock,
    private val wishlistHearts: WishlistHearts = WishlistHearts.NONE,
) : QueryUseCase<GetCheckoutContextUc.Qry, CheckoutContextDto, GetCheckoutContextUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property cartId the customer's cart, as OpenCartUc returned it.
     * @property addressId the `addressId` query parameter as it arrived; null when not sent (the default address).
     */
    data class Qry(val userId: UserId, val cartId: String, val addressId: String?)

    override suspend fun execute(query: Qry): Result<CheckoutContextDto, Failure> {
        logger.info("GetCheckoutContextUc.execute userId=${query.userId.value} cartId=${query.cartId} addressId=${query.addressId}")
        return transactionManager.inTransaction {
            val user = userRepository.findById(query.userId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.AccountGone)
            val cart = cartRepository.find(CartPublicId.constructFromPersisted(query.cartId))
                ?: error("The cart ${query.cartId} of user ${query.userId.value} was opened a moment ago, yet it isn't there")
            check(cart.owner == CartOwner.User(query.userId)) { "Cart ${query.cartId} is not the cart of user ${query.userId.value}" }
            if (cart.lines.isEmpty()) return@inTransaction Result.failure(Failure.CartEmpty)

            val addresses = addressBookRepository.find(query.userId).addresses.defaultFirst()
            val selected = if (query.addressId == null) {
                addresses.firstOrNull { it.isDefault }
            } else {
                addressIdOf(query.addressId)?.let { id -> addresses.firstOrNull { it.id == id } }
                    ?: return@inTransaction Result.failure(Failure.AddressNotFound)
            }

            val settings = shopSettingsRepository.get()
            val now = clock.instant()
            // The order joins the customer's reservation only at its address: the one selected here.
            val standing = selected?.let { reservations.standingOf(query.userId, it.id, now) } ?: reservations.standingOf(query.userId, null, now).copy(joins = null)
            val offers = offerQmRepository.findOffers(cart.lines.map { it.variantId })
            val discount = discounts.appliedTo(cart, offers.mapValues { it.value.offer }, now)
            Result.success(
                CheckoutContextDto(
                    cart = withHearts(cartDtoOf(cart, offers, settings, now, standing, discount), cart, wishlistHearts),
                    addresses = geoRepository.addressDtosOf(addresses),
                    selectedAddressId = selected?.id?.value,
                    selectedMessenger = user.defaultMessenger,
                    messengerPhone = (user.defaultMessengerPhone ?: user.activePhone).value,
                    paymentGateways = paymentGateways.checkoutOptions(settings),
                ),
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null) : ResultFailure(code, kind, field = field) {
        /** A token of an account that no longer exists: there is nobody to check out, so log in again. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)

        /** Not a single line in the cart: there is nothing to check out. */
        data object CartEmpty : Failure("CART_EMPTY", FailureKind.BUSINESS_RULE)

        /** The `addressId` asked for is not one of the customer's addresses (contract: 400 on the field). */
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.INVALID, field = "addressId")
    }
}
