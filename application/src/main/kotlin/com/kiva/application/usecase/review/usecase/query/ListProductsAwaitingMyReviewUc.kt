package com.kiva.application.usecase.review.usecase.query

import com.kiva.application.dto.OrderDetailDto
import com.kiva.application.repository.ProductBriefQm
import com.kiva.application.usecase.review.ReviewsAwaiting
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.repository.ColorRepository
import java.time.Instant

/**
 * «منتظر نظر تو» (`GET /me/reviews/pending-products`; roadmap section 13.2, decision 23): the lines of the customer's
 * delivered orders (not cancelled since) whose product is published and that they have no live review of; one line per
 * product (the latest delivery), the latest delivered first, at most [LIMIT] (ReviewsAwaiting, which the account's
 * header counts too). The color as it was bought.
 */
class ListProductsAwaitingMyReviewUc(
    private val transactionManager: TransactionManager,
    private val reviewsAwaiting: ReviewsAwaiting,
    private val colorRepository: ColorRepository,
) : QueryUseCase<ListProductsAwaitingMyReviewUc.Qry, List<ListProductsAwaitingMyReviewUc.Awaiting>, Nothing> {

    private val logger = getLogger()

    data class Qry(val userId: UserId)

    /** A purchase to review: the line of the order, its product as it is now, the color as bought. */
    data class Awaiting(val orderCode: String, val lineId: Int, val product: ProductBriefQm, val color: OrderDetailDto.Color, val deliveredOn: Instant)

    override suspend fun execute(query: Qry): Result<List<Awaiting>, Nothing> {
        logger.info("ListProductsAwaitingMyReviewUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            val palette = colorRepository.findAll().associateBy { it.key }
            val awaiting = reviewsAwaiting.of(query.userId).map { (line, product) ->
                val inPalette = palette[line.colorKey]
                Awaiting(
                    line.orderCode.value, line.lineId, product,
                    OrderDetailDto.Color(line.colorKey.value, line.colorName, line.colorHex, inPalette?.isLight ?: false, inPalette?.sortOrder ?: Int.MAX_VALUE),
                    checkNotNull(line.deliveredOn),
                )
            }
            Result.success(awaiting)
        }
    }

    companion object {
        const val LIMIT = ReviewsAwaiting.LIMIT
    }
}
