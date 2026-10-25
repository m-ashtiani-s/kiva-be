package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.review.usecase.command.CreateReviewUc
import com.kiva.application.usecase.review.usecase.command.VoteReviewHelpfulUc
import com.kiva.application.usecase.review.usecase.query.GetMyReviewUc
import com.kiva.application.usecase.review.usecase.query.GetReviewSummaryUc
import com.kiva.application.usecase.review.usecase.query.ListProductReviewsUc
import com.kiva.application.usecase.review.usecase.query.ReviewSorts
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.AccessPrincipal
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CreateReviewHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ReviewHelpfulHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.RatingSummaryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewVoteHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of a product's reviews, as the routes need them. */
class ReviewUseCases(
    val create: CreateReviewUc,
    val getMine: GetMyReviewUc,
    val list: ListProductReviewsUc,
    val summary: GetReviewSummaryUc,
    val vote: VoteReviewHelpfulUc,
)

/** The path parameter of a product. */
private const val PRODUCT_ID = "productId"

/** The path parameter of a review. */
private const val REVIEW_ID = "reviewId"

/**
 * A product's reviews (contract tag `Reviews`): the public list (a login optional, but a broken token is 401) and the
 * summary, writing one and the helpful vote (a login needed). Mounted under `/api/v1`. A product id that can't be one,
 * or of a product not in the shop, is 404 PRODUCT_NOT_FOUND.
 */
fun Route.webV1Reviews(useCases: ReviewUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS, optional = true) {
        get("/products/{$PRODUCT_ID}/reviews") {
            val pageable = call.pageable(allowedSorts = ReviewSorts.names, defaultSort = ReviewSorts.DEFAULT, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = ListProductReviewsUc.Qry(
                call.principal<AccessPrincipal>()?.userId, call.parameters[PRODUCT_ID].orEmpty(), pageable, params["rating"], params["withMedia"], params["verifiedOnly"],
            )
            call.respondResult(useCases.list.execute(query)) { ReviewListHttpResponse.from(it, mediaUrls) }
        }
    }

    // Public: the rating alone, not cached (every approval changes it).
    get("/products/{$PRODUCT_ID}/reviews/summary") {
        call.respondResult(useCases.summary.execute(GetReviewSummaryUc.Qry(call.parameters[PRODUCT_ID].orEmpty()))) { RatingSummaryHttpResponse.from(it) }
    }

    authenticate(ACCESS) {
        put("/reviews/{$REVIEW_ID}/helpful") {
            val request = call.receive<ReviewHelpfulHttpRequest>()
            val command = VoteReviewHelpfulUc.Cmd(call.accessPrincipal().userId, call.parameters[REVIEW_ID].orEmpty(), request.helpful)
            call.respondResult(useCases.vote.execute(command)) { ReviewVoteHttpResponse(it.helpfulCount, it.myVote) }
        }

        post("/products/{$PRODUCT_ID}/reviews") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<CreateReviewHttpRequest>()
            val command = CreateReviewUc.Cmd(
                userId = userId,
                productId = call.parameters[PRODUCT_ID].orEmpty(),
                rating = request.rating,
                authorName = request.authorName,
                contact = request.contact,
                text = request.text,
                orderCode = request.orderCode,
                orderItemId = request.orderItemId,
                mediaIds = request.mediaIds?.map { it.orEmpty() },
            )
            val id = useCases.create.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getMine.execute(GetMyReviewUc.Qry(userId, id)), HttpStatusCode.Created) { ReviewHttpResponse.from(it, mediaUrls) }
        }
    }
}
