package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.review.usecase.command.DeleteReviewReplyUc
import com.kiva.application.usecase.review.usecase.command.ModerateReviewUc
import com.kiva.application.usecase.review.usecase.command.ReplyToReviewUc
import com.kiva.application.usecase.review.usecase.query.FindAdminReviewsUc
import com.kiva.application.usecase.review.usecase.query.GetAdminReviewUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ReviewModerationHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ReviewReplyHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminReviewListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ReviewHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's reviews, as the routes need them. */
class AdminReviewUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findReviews: FindAdminReviewsUc,
    val getReview: GetAdminReviewUc,
    val moderate: ModerateReviewUc,
    val reply: ReplyToReviewUc,
    val deleteReply: DeleteReviewReplyUc,
)

/** The path parameter every panel review route names its review by. */
private const val REVIEW_ID = "reviewId"

/** The one order of the queue (the contract has no `sort`). */
private const val NEWEST = "newest"

/**
 * The panel's reviews (contract tag `Admin · Support`, `[ADMIN, SUPPORT]`): the queue, moderation and Kiva's reply.
 * Mounted under `/api/v1`. A review id that can't be one, or nobody has, is 404 REVIEW_NOT_FOUND. Moderation and the
 * reply answer with the review as the panel sees it now.
 */
fun Route.webV1AdminReviews(useCases: AdminReviewUseCases, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.SUPPORT) {
        get("/admin/reviews") {
            val pageable = call.pageable(allowedSorts = setOf(NEWEST), defaultSort = NEWEST, defaultSize = 20, maxSize = 100)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindAdminReviewsUc.Qry(pageable, params["status"], params["productId"], params["hasReply"])
            call.respondResult(useCases.findReviews.execute(query)) { AdminReviewListHttpResponse.from(it, mediaUrls) }
        }

        post("/admin/reviews/{$REVIEW_ID}/moderation") {
            val id = call.parameters[REVIEW_ID].orEmpty()
            val request = call.receive<ReviewModerationHttpRequest>()
            val command = ModerateReviewUc.Cmd(id, request.action, request.rejectionReason, request.reply, call.panelActor())
            useCases.moderate.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getReview.execute(GetAdminReviewUc.Qry(id))) { ReviewHttpResponse.from(it, mediaUrls) }
        }

        put("/admin/reviews/{$REVIEW_ID}/reply") {
            val id = call.parameters[REVIEW_ID].orEmpty()
            val request = call.receive<ReviewReplyHttpRequest>()
            useCases.reply.execute(ReplyToReviewUc.Cmd(id, request.text, call.panelActor())).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getReview.execute(GetAdminReviewUc.Qry(id))) { ReviewHttpResponse.from(it, mediaUrls) }
        }

        delete("/admin/reviews/{$REVIEW_ID}/reply") {
            call.respondResult(useCases.deleteReply.execute(DeleteReviewReplyUc.Cmd(call.parameters[REVIEW_ID].orEmpty(), call.panelActor())))
        }
    }
}
