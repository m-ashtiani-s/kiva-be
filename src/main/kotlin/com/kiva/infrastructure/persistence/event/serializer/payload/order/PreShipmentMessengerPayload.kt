package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import kotlinx.serialization.Serializable

/**
 * A [PreShipmentMessenger]. Personal data, like the address.
 *
 * Stored in the order's OrderPlaced event, and in the same shape in the order read model (orderqm_tbl), so both read
 * back through this class. Never renamed: see EventPayload.
 */
@Serializable
data class PreShipmentMessengerPayload(
    val channel: String,
    val phone: String,
    val username: String?,
    val note: String?,
) {
    constructor(messenger: PreShipmentMessenger) : this(
        channel = messenger.channel.name,
        phone = messenger.phone.value,
        username = messenger.username,
        note = messenger.note,
    )

    fun toDomain(): PreShipmentMessenger = PreShipmentMessenger.constructFromPersisted(
        channel = MessengerChannel.valueOf(channel),
        phone = IranianMobileNumber.constructFromPersisted(phone),
        username = username,
        note = note,
    )

    override fun toString(): String = "PreShipmentMessengerPayload(channel=$channel)"
}
