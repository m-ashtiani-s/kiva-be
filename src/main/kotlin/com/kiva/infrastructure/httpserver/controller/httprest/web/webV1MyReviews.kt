package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.review.usecase.command.DeleteMyReviewUc
import com.kiva.application.usecase.review.usecase.command.UpdateMyReviewUc
import com.kiva.application.usecase.review.usecase.query.GetMyReviewUc
import com.kiva.application.usecase.review.usecase.query.ListMyReviewsUc
import com.kiva.application.usecase.review.usecase.query.ListProductsAwaitingMyReviewUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateReviewHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MyReviewListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewablePurchaseHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch

/** The use cases of a customer's own reviews, as the routes need them. */
class MyReviewUseCases(
    val listMine: ListMyReviewsUc,
    val getMine: GetMyReviewUc,
    val update: UpdateMyReviewUc,
    val delete: DeleteMyReviewUc,
    val awaiting: ListProductsAwaitingMyReviewUc,
)

/** The path parameter of a review. */
private const val REVIEW_ID = "reviewId"

/**
 * A customer's own reviews (contract tag `Reviews`): «نظرات من», editing and deleting one, and «منتظر نظر تو». Mounted
 * under `/api/v1`; a login is needed, and a review is always the token's customer's (someone else's is the same 404 as
 * none). Writing one is `POST /products/{productId}/reviews` (webV1Reviews).
 */
fun Route.webV1MyReviews(useCases: MyReviewUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        // No sort of its own: newest first.
        get("/me/reviews") {
            val pageable = call.pageable(allowedSorts = setOf(NEWEST), defaultSort = NEWEST, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            call.respondResult(useCases.listMine.execute(ListMyReviewsUc.Qry(call.accessPrincipal().userId, pageable))) { MyReviewListHttpResponse.from(it, mediaUrls) }
        }

        // A plain array, the latest delivered first, at most 50.
        get("/me/reviews/pending-products") {
            call.respondResult(useCases.awaiting.execute(ListProductsAwaitingMyReviewUc.Qry(call.accessPrincipal().userId))) { lines ->
                lines.map { ReviewablePurchaseHttpResponse.from(it, mediaUrls) }
            }
        }

        patch("/me/reviews/{$REVIEW_ID}") {
            val userId = call.accessPrincipal().userId
            val request = call.receive<UpdateReviewHttpRequest>()
            val command = UpdateMyReviewUc.Cmd(userId, call.parameters[REVIEW_ID].orEmpty(), request.rating, request.authorName, request.text)
            val id = useCases.update.execute(command).unwrapOr { return@patch call.respondFailure(it) }
            call.respondResult(useCases.getMine.execute(GetMyReviewUc.Qry(userId, id))) { ReviewHttpResponse.from(it, mediaUrls) }
        }

        delete("/me/reviews/{$REVIEW_ID}") {
            call.respondResult(useCases.delete.execute(DeleteMyReviewUc.Cmd(call.accessPrincipal().userId, call.parameters[REVIEW_ID].orEmpty())))
        }
    }
}

private const val NEWEST = "newest"
