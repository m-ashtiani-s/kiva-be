package com.kiva.domain.aggregate.returnrequest

import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.refund.model.valueobject.RefundMethod
import com.kiva.domain.aggregate.returnrequest.entity.ReturnRequest
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnText
import com.kiva.domain.aggregate.returnrequest.repository.ReturnIdGenerator
import java.time.Instant

/**
 * What a return alone can't know when it is asked for (roadmap section 12.2, decisions 3, 5 and 6): whether its order may
 * be returned, with these lines, and whether its photos are the customer's own evidence. It asks the order
 * (Order.requestReturn records the return on the order), hands out the new return's id, and makes the return.
 *
 * It never saves and never opens a transaction; the use case holds the order's row locked (lock 9) while it asks, so two
 * returns asked at once are counted one after the other, and the second finds the order RETURN_REQUESTED.
 */
class ReturnDomainService(
    private val returnIdGenerator: ReturnIdGenerator,
) {
    private val logger = getLogger()

    /**
     * A new REQUESTED return of [items] of [order], which [userId] owns and may return now; the order records it
     * (OrderReturnRequested) and holds it open. [mediaIds] are the photos and videos the customer sent, [found] the ones of
     * them that exist (the use case read them).
     *
     * Refused in the contract's order (`POST /me/orders/{orderCode}/returns`), each step with every problem it finds,
     * each under its field: a photo that isn't this customer's return evidence ([OpenFailure.InvalidInput], 400
     * MEDIA_NOT_FOUND, the same answer as one that isn't there); an order that may not be returned now
     * ([OpenFailure.NotReturnable], [OpenFailure.WindowExpired], 422); then the lines (ReturnPolicy.itemProblems), no
     * evidence for the shop's fault (EVIDENCE_REQUIRED) and no description for «other» (DESCRIPTION_REQUIRED) together (400).
     */
    fun open(
        order: Order,
        userId: UserId,
        items: List<ReturnItem>,
        reason: ReturnReason,
        description: ReturnText?,
        mediaIds: List<MediaId>,
        found: List<MediaAsset>,
        refundMethod: RefundMethod,
        now: Instant,
    ): Result<ReturnRequest, OpenFailure> {
        logger.info("open orderId=${order.id.value} lines=${items.map { "${it.lineId}x${it.quantity}" }} reason=$reason media=${mediaIds.size}")
        require(order.userId == userId) { "User ${userId.value} returns order ${order.id.value} of another customer" }
        require(mediaIds.distinct().size == mediaIds.size && mediaIds.size <= ReturnPolicy.MAX_MEDIA) { "Order ${order.id.value}: ${mediaIds.size} media" }

        val evidence = found.filter { it.purpose == MediaPurpose.RETURN_EVIDENCE && it.uploadedBy == userId }.map { it.id }.toSet()
        val mediaProblems = mediaIds.mapIndexedNotNull { i, mediaId -> FieldFailure("mediaIds.$i", ReturnPolicy.Problem.MediaNotFound).takeIf { mediaId !in evidence } }
        if (mediaProblems.isNotEmpty()) return Result.failure(OpenFailure.InvalidInput(mediaProblems))

        order.returnRefusal(now)?.let { return Result.failure(failureOf(it)) }

        val problems = ReturnPolicy.itemProblems(order.lines, items) +
            listOfNotNull(
                FieldFailure("mediaIds", ReturnPolicy.Problem.EvidenceRequired).takeIf { ReturnPolicy.requiresEvidence(reason) && mediaIds.isEmpty() },
                FieldFailure("description", ReturnPolicy.Problem.DescriptionRequired).takeIf { ReturnPolicy.requiresDescription(reason) && description == null },
            )
        if (problems.isNotEmpty()) return Result.failure(OpenFailure.InvalidInput(problems))

        val id: ReturnId = returnIdGenerator.nextReturnId()
        order.requestReturn(id, items, userId, now).failureOrNull()?.let { error("Order ${order.id.value} refused return ${id.value} it was checked for: ${it.code}") }
        return Result.success(ReturnRequest.request(id, order.id, order.code, userId, reason, description, items, mediaIds, refundMethod, now))
    }

    private fun failureOf(refusal: Order.RequestReturnFailure): OpenFailure = when (refusal) {
        is Order.RequestReturnFailure.NotReturnable -> OpenFailure.NotReturnable(refusal.meta)
        is Order.RequestReturnFailure.WindowExpired -> OpenFailure.WindowExpired(refusal.returnableUntil)
        is Order.RequestReturnFailure.ItemsInvalid -> error("The order refuses a return's lines only after it may be returned")
    }

    sealed class OpenFailure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Not delivered, a return open already, returned once already, or nothing returnable (422). */
        data class NotReturnable(val details: Map<String, Any>) : OpenFailure("ORDER_NOT_RETURNABLE", FailureKind.BUSINESS_RULE, meta = details)

        /** Its time to return ended at [returnableUntil] (422). */
        data class WindowExpired(val returnableUntil: Instant) :
            OpenFailure("RETURN_WINDOW_EXPIRED", FailureKind.BUSINESS_RULE, meta = mapOf("returnableUntil" to returnableUntil.toString()))

        /** Every problem of the request, each under its field (400). */
        data class InvalidInput(val problems: List<ResultFailure>) : OpenFailure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
