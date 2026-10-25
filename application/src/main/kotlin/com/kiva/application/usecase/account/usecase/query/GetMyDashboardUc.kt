package com.kiva.application.usecase.account.usecase.query

import com.kiva.application.dto.ReservationInfoDto
import com.kiva.application.dto.UserDto
import com.kiva.application.repository.OrderQmRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderTab
import com.kiva.application.usecase.reservation.Reservations
import com.kiva.application.usecase.review.ReviewsAwaiting
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.review.repository.ReviewRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistRepository
import java.time.Clock

/**
 * The account's header (`GET /me/dashboard`; roadmap section 14.6, decision 16): the greeting's name, four stat cards
 * and the side menu's counts, each read the way the page it links to reads it, so the two never disagree: the orders'
 * tabs (`counts.all`, `counts.current`), the last batch of photos sent of each order, the visible wishlist, the address
 * book, «نظرهای من» (`counts.total`), the live «رزرو ۴ روزه» and «منتظر نظر تو». One read-only transaction.
 */
class GetMyDashboardUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val orderQmRepository: OrderQmRepository,
    private val wishlistRepository: WishlistRepository,
    private val addressBookRepository: AddressBookRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewsAwaiting: ReviewsAwaiting,
    private val reservations: Reservations,
    private val clock: Clock,
) : QueryUseCase<GetMyDashboardUc.Qry, GetMyDashboardUc.Dashboard, GetMyDashboardUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token. */
    data class Qry(val userId: UserId)

    data class Dashboard(
        val user: UserDto,
        val ordersTotal: Int,
        val ordersActive: Int,
        val mediaReceived: Int,
        val wishlistCount: Int,
        val addressCount: Int,
        val reviewCount: Int,
        val activeReservation: ReservationInfoDto?,
        val pendingReviewCount: Int,
    )

    override suspend fun execute(query: Qry): Result<Dashboard, Failure> {
        logger.info("GetMyDashboardUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            val user = userRepository.findById(query.userId)?.takeUnless { it.isDeleted } ?: return@inTransaction Result.failure(Failure.AccountGone)
            val byStatus = orderQmRepository.countByStatusOfCustomer(query.userId)
            Result.success(
                Dashboard(
                    user = UserDto.from(user),
                    ordersTotal = byStatus.values.sum().toInt(),
                    ordersActive = byStatus.filterKeys { OrderTab.of(it) == OrderTab.CURRENT }.values.sum().toInt(),
                    mediaReceived = orderQmRepository.sentMediaCountOf(query.userId),
                    wishlistCount = wishlistRepository.countVisible(query.userId),
                    addressCount = addressBookRepository.find(query.userId).addresses.size,
                    reviewCount = reviewRepository.countsOfUser(query.userId).values.sum(),
                    activeReservation = reservations.activeInfoOf(query.userId, clock.instant()),
                    pendingReviewCount = reviewsAwaiting.of(query.userId).size,
                ),
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** A token of an account that no longer exists, as for `GET /me`. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)
    }
}
