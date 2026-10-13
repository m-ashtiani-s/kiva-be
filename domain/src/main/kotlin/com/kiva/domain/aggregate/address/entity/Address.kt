package com.kiva.domain.aggregate.address.entity

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.AddressTitle
import com.kiva.domain.aggregate.address.model.valueobject.GeoPoint
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import java.time.Instant

/**
 * One saved address of a customer. Lives inside its [AddressBook]: only the book changes it, because whether it is the
 * default depends on the other addresses. Immutable: a change is a new copy put in its place.
 *
 * Its identity is [id]. [toString] shows no personal data.
 */
class Address private constructor(
    val id: AddressId,
    val title: AddressTitle?,
    val provinceId: ProvinceId,
    val cityId: CityId,
    val line: AddressLine,
    val plaque: Plaque?,
    val unit: UnitNumber?,
    val postalCode: PostalCode,
    val recipientName: RecipientName,
    val recipientPhone: IranianMobileNumber,
    val isSelfRecipient: Boolean,
    val location: GeoPoint?,
    val isDefault: Boolean,
    val createdOn: Instant,
    val updatedOn: Instant,
) {
    /** The same address with what the customer filled in now; it keeps its id, its creation time and [isDefault]. */
    internal fun with(input: AddressInput, isDefault: Boolean, now: Instant): Address =
        Address(
            id, input.title, input.provinceId, input.cityId, input.line, input.plaque, input.unit, input.postalCode,
            input.recipientName, input.recipientPhone, input.isSelfRecipient, input.location, isDefault, createdOn, now,
        )

    internal fun withDefault(isDefault: Boolean, now: Instant): Address =
        Address(
            id, title, provinceId, cityId, line, plaque, unit, postalCode, recipientName, recipientPhone, isSelfRecipient, location,
            isDefault, createdOn, now,
        )

    override fun equals(other: Any?): Boolean = other is Address && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Address(id=${id.value}, isDefault=$isDefault)"

    companion object {
        internal fun new(id: AddressId, input: AddressInput, isDefault: Boolean, now: Instant): Address =
            Address(
                id, input.title, input.provinceId, input.cityId, input.line, input.plaque, input.unit, input.postalCode,
                input.recipientName, input.recipientPhone, input.isSelfRecipient, input.location, isDefault, now, now,
            )

        fun constructFromPersisted(
            id: AddressId,
            title: AddressTitle?,
            provinceId: ProvinceId,
            cityId: CityId,
            line: AddressLine,
            plaque: Plaque?,
            unit: UnitNumber?,
            postalCode: PostalCode,
            recipientName: RecipientName,
            recipientPhone: IranianMobileNumber,
            isSelfRecipient: Boolean,
            location: GeoPoint?,
            isDefault: Boolean,
            createdOn: Instant,
            updatedOn: Instant,
        ): Address = Address(
            id, title, provinceId, cityId, line, plaque, unit, postalCode, recipientName, recipientPhone, isSelfRecipient, location,
            isDefault, createdOn, updatedOn,
        )
    }
}
