package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import kotlinx.serialization.Serializable

/**
 * A [GiftNote]. The message is personal, like a note.
 *
 * Stored in the order's OrderPlaced event, and in the same shape in the order read model (orderqm_tbl), so both read
 * back through this class. Never renamed: see EventPayload.
 */
@Serializable
data class GiftNotePayload(
    val message: String?,
    val hidePrices: Boolean,
) {
    constructor(gift: GiftNote) : this(message = gift.message, hidePrices = gift.hidePrices)

    fun toDomain(): GiftNote = GiftNote.constructFromPersisted(message, hidePrices)

    override fun toString(): String = "GiftNotePayload(hidePrices=$hidePrices)"
}
