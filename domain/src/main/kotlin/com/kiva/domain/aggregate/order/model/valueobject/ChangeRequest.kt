package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId

/** What the customer wants changed after seeing the photos (the contract's `PreShipmentFeedbackRequest.changeType`). */
enum class ChangeType {
    /** Another color of the same product. */
    COLOR,

    /** A color of another product. */
    MODEL,

    /** One line taken out; the only line of the order is the whole order cancelled. */
    CANCEL_ITEM,

    /** Anything else, said in the note; staff do it by hand. */
    OTHER,
    ;

    /** It names the line it changes. */
    val hasLine: Boolean get() = this != OTHER

    /** It swaps the line for another color ([COLOR] or [MODEL]). */
    val swapsColor: Boolean get() = this == COLOR || this == MODEL

    companion object {
        /** The type as the contract sends it, exactly; anything else is CHANGE_TYPE_INVALID. */
        fun makeNew(raw: String): Result<ChangeType, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "changeType") {
        data object Invalid : Failure("CHANGE_TYPE_INVALID")
    }
}

/**
 * The customer's change request, open until staff resolve it (roadmap section 8.3): its [type], the line it is about
 * ([lineId], the contract's `orderItemId`), the color wanted instead ([desiredVariantId]) and what they wrote ([note]).
 *
 * Its shape is the type's (roadmap section 8.2, row 2): COLOR and MODEL name a line and a color, CANCEL_ITEM a line,
 * OTHER a note. The use case checked that, so anything else is a bug. [note] is personal: [toString] hides it.
 */
data class ChangeRequest(val type: ChangeType, val lineId: Int?, val desiredVariantId: VariantId?, val note: StatusNote?) {
    init {
        require(type.hasLine == (lineId != null)) { "A $type change ${if (type.hasLine) "names" else "names no"} line: $lineId" }
        require(lineId == null || lineId >= 1) { "Invalid order line id $lineId" }
        require(type.swapsColor == (desiredVariantId != null)) { "A $type change ${if (type.swapsColor) "names" else "names no"} color" }
        require(type != ChangeType.OTHER || note != null) { "An OTHER change says what it is" }
    }

    override fun toString(): String = "ChangeRequest(type=$type, lineId=$lineId, desiredVariantId=${desiredVariantId?.value})"
}

/**
 * The color a COLOR or MODEL change asks for, as the catalog has it: its [productId] tells another color of the same
 * product from a color of another one. The use case reads it from the read model (the order can't know the catalog).
 */
data class ChangeTarget(val variantId: VariantId, val productId: ProductId)
