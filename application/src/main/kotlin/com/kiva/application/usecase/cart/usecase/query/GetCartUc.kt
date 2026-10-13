package com.kiva.application.usecase.cart.usecase.query

import com.kiva.application.dto.CartDto
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.usecase.cart.cartDtoOf
import com.kiva.application.usecase.discount.Discounts
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.cart.model.valueobject.CartOwner
import com.kiva.domain.aggregate.cart.model.valueobject.CartPublicId
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The whole cart as the shopper sees it: the body of every cart answer, built after the command that changed or opened
 * it (see cartDtoOf, which the checkout shares).
 *
 * The cart is asked for by the id the command just returned, never by the shopper: there is no owner to check here.
 */
class GetCartUc(
    private val transactionManager: TransactionManager,
    private val cartRepository: CartRepository,
    private val offerQmRepository: OfferQmRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val reservations: Reservations,
    private val discounts: Discounts,
    private val clock: Clock,
) : QueryUseCase<GetCartUc.Qry, CartDto, GetCartUc.Failure> {

    private val logger = getLogger()

    /** [cartId] as the command returned it. */
    data class Qry(val cartId: String)

    override suspend fun execute(query: Qry): Result<CartDto, Failure> {
        logger.info("GetCartUc.execute cartId=${query.cartId}")
        return transactionManager.inTransaction {
            val cart = cartRepository.find(CartPublicId.constructFromPersisted(query.cartId))
                ?: return@inTransaction Result.failure(Failure.CartNotFound)
            val offers = offerQmRepository.findOffers(cart.lines.map { it.variantId })
            val now = clock.instant()
            // A customer's order from the cart goes to their default address until checkout picks another.
            val standing = (cart.owner as? CartOwner.User)?.let { reservations.standingOf(it.userId, addressId = null, now) } ?: Reservations.Standing.NONE
            val discount = discounts.appliedTo(cart, offers.mapValues { it.value.offer }, now)
            Result.success(cartDtoOf(cart, offers, shopSettingsRepository.get(), now, standing, discount))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Only if the cart was deleted between the command and this query (a merge after a login, at the same moment). */
        data object CartNotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
