package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.review.usecase.command.CreateReviewUc
import com.kiva.application.usecase.review.usecase.query.GetMyReviewUc
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CreateReviewHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** The use cases of a product's reviews, as the routes need them. */
class ReviewUseCases(
    val create: CreateReviewUc,
    val getMine: GetMyReviewUc,
)

/** The path parameter of a product. */
private const val PRODUCT_ID = "productId"

/**
 * A product's reviews (contract tag `Reviews`): writing one (a login is needed). Mounted under `/api/v1`. A product id
 * that can't be one, or of a product not in the shop, is 404 PRODUCT_NOT_FOUND. The answer is the new review as its
 * author sees it (PENDING, `isMine`).
 */
fun Route.webV1Reviews(useCases: ReviewUseCases, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
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
