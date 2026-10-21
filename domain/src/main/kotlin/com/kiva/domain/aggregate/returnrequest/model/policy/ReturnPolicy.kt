package com.kiva.domain.aggregate.returnrequest.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.ListItemDuplicate
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReason
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnShippingPayer
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Duration
import java.time.Instant

/**
 * The rules of asking for a return (roadmap section 12.2, decisions 3 to 5 and 9), for whoever needs them: the order keeps
 * the lines' rule (Order.requestReturn), ReturnDomainService the evidence and the description, the use cases the limits
 * of a form. Pure.
 */
object ReturnPolicy {

    /** At most this many lines in one return, and of one line, and of a receipt's numbers (QUANTITY_INVALID, NUMBER_OUT_OF_RANGE). */
    const val MAX_ITEMS = 20
    const val MAX_QUANTITY = 20

    /** At most this many photos and videos of the problem (TOO_MANY_ITEMS). */
    const val MAX_MEDIA = 6

    /** The carriers that collect a parcel: the courier doesn't run yet (SHIPPING_METHOD_NOT_SUPPORTED). */
    val PICKUP_CARRIERS: Set<ShippingMethodCode> = setOf(ShippingMethodCode.TIPAX, ShippingMethodCode.POST)

    /** A pickup is planned from an hour before now (written down a little late) up to 30 days on (PICKUP_TIME_INVALID). */
    val PICKUP_EARLIEST: Duration = Duration.ofHours(1)
    val PICKUP_LATEST: Duration = Duration.ofDays(30)

    /** Who pays the way back unless staff decide otherwise when they approve it: the shop, for its own fault (decision 4). */
    fun defaultPayerOf(reason: ReturnReason): ReturnShippingPayer = if (reason.isKivaFault) ReturnShippingPayer.KIVA else ReturnShippingPayer.CUSTOMER

    /** The shop's fault needs a photo or video of the problem: the shop pays the way back, so staff see it first (decision 5). */
    fun requiresEvidence(reason: ReturnReason): Boolean = reason.isKivaFault

    /** «Other» needs a few words of what happened (decision 5). */
    fun requiresDescription(reason: ReturnReason): Boolean = reason == ReturnReason.OTHER

    /** [scheduledFor] is within [PICKUP_EARLIEST] before [now] and [PICKUP_LATEST] after it. */
    fun pickupTimeFits(scheduledFor: Instant, now: Instant): Boolean =
        !scheduledFor.isBefore(now.minus(PICKUP_EARLIEST)) && !scheduledFor.isAfter(now.plus(PICKUP_LATEST))

    /**
     * What is wrong with [items] against the order's [lines] (decision 3), each under its field: a line the order doesn't
     * have (ORDER_ITEM_NOT_FOUND), one that wasn't returnable when bought (ITEM_NOT_RETURNABLE), one named twice
     * (LIST_ITEM_DUPLICATE), more of it than was bought (RETURN_QUANTITY_EXCEEDS with `meta.maxQuantity`). Empty when
     * nothing is.
     */
    fun itemProblems(lines: List<OrderLine>, items: List<ReturnItem>): List<ResultFailure> {
        val byId = lines.associateBy { it.lineId }
        val seen = mutableSetOf<Int>()
        return items.flatMapIndexed { i, item ->
            val line = byId[item.lineId]
            when {
                !seen.add(item.lineId) -> listOf(FieldFailure("items.$i.orderItemId", ListItemDuplicate))
                line == null -> listOf(FieldFailure("items.$i.orderItemId", Problem.UnknownLine))
                !line.returnable -> listOf(FieldFailure("items.$i.orderItemId", Problem.ItemNotReturnable))
                item.quantity > line.quantity.value -> listOf(FieldFailure("items.$i.quantity", Problem.QuantityExceeds(line.quantity.value)))
                else -> emptyList()
            }
        }
    }

    /** What can be wrong with a return asked for; the field is added where it is found. */
    sealed class Problem(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, meta = meta) {
        data object UnknownLine : Problem("ORDER_ITEM_NOT_FOUND")
        data object ItemNotReturnable : Problem("ITEM_NOT_RETURNABLE")
        data class QuantityExceeds(val maxQuantity: Int) : Problem("RETURN_QUANTITY_EXCEEDS", mapOf("maxQuantity" to maxQuantity))
        data object EvidenceRequired : Problem("EVIDENCE_REQUIRED")
        data object DescriptionRequired : Problem("DESCRIPTION_REQUIRED")

        /** Not there, not a return's evidence, or someone else's: one answer for all three (decision 6). */
        data object MediaNotFound : Problem("MEDIA_NOT_FOUND")
    }
}
