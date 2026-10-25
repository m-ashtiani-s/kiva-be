package com.kiva.infrastructure.persistence.event.serializer.payload.order

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import kotlinx.serialization.Serializable

/**
 * An [AddressSnapshot]. Personal data: it is in the database for the order's sake, never in a log.
 *
 * Stored in the order's OrderPlaced event, and in the same shape in the order read model (orderqm_tbl), so both read
 * back through this class. Never renamed: see EventPayload. The personal parts are null only in the read model of an
 * erased order (OrderPersonalDataErased, phase 14); an OrderPlaced always has them.
 */
@Serializable
data class AddressSnapshotPayload(
    val addressId: Long?,
    val provinceName: String,
    val cityName: String,
    val addressLine: String?,
    val plaque: String?,
    val unit: String?,
    val postalCode: String?,
    val recipientName: String?,
    val recipientPhone: String?,
) {
    constructor(address: AddressSnapshot) : this(
        addressId = address.addressId?.value,
        provinceName = address.provinceName,
        cityName = address.cityName,
        addressLine = address.line?.value,
        plaque = address.plaque?.value,
        unit = address.unit?.value,
        postalCode = address.postalCode?.value,
        recipientName = address.recipientName?.value,
        recipientPhone = address.recipientPhone?.value,
    )

    fun toDomain(): AddressSnapshot = AddressSnapshot.constructFromPersisted(
        addressId = addressId?.let { AddressId.constructFromPersisted(it) },
        provinceName = provinceName,
        cityName = cityName,
        line = addressLine?.let { AddressLine.constructFromPersisted(it) },
        plaque = plaque?.let { Plaque.constructFromPersisted(it) },
        unit = unit?.let { UnitNumber.constructFromPersisted(it) },
        postalCode = postalCode?.let { PostalCode.constructFromPersisted(it) },
        recipientName = recipientName?.let { RecipientName.constructFromPersisted(it) },
        recipientPhone = recipientPhone?.let { IranianMobileNumber.constructFromPersisted(it) },
    )

    override fun toString(): String = "AddressSnapshotPayload(addressId=$addressId)"
}
