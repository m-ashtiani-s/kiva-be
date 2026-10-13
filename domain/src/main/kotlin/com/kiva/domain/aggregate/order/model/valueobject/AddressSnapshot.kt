package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.address.entity.Address
import com.kiva.domain.aggregate.address.model.AddressInput
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber

/**
 * Where an order goes, copied when it is placed: editing or deleting the saved address later never changes an order
 * (the contract's AddressSnapshot). The province and city are kept by name, as they were. `fullText` is not kept: the
 * HTTP layer writes it from these parts, as it does for the address book, so the Persian words stay out of the domain.
 *
 * Personal data: [toString] shows nothing of it.
 *
 * @property addressId the saved address it was copied from; null for a new address that wasn't saved.
 */
class AddressSnapshot private constructor(
    val addressId: AddressId?,
    val provinceName: String,
    val cityName: String,
    val line: AddressLine,
    val plaque: Plaque?,
    val unit: UnitNumber?,
    val postalCode: PostalCode,
    val recipientName: RecipientName,
    val recipientPhone: IranianMobileNumber,
) {
    init {
        require(provinceName.isNotBlank() && cityName.isNotBlank()) { "An address snapshot needs its province and city" }
    }

    private fun parts(): List<Any?> = listOf(addressId, provinceName, cityName, line, plaque, unit, postalCode, recipientName, recipientPhone)

    override fun equals(other: Any?): Boolean = other is AddressSnapshot && other.parts() == parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = "AddressSnapshot(addressId=${addressId?.value})"

    companion object {
        /** A saved address of the customer, with the names of its province and city. */
        fun of(address: Address, provinceName: String, cityName: String): AddressSnapshot = AddressSnapshot(
            address.id, provinceName, cityName, address.line, address.plaque, address.unit, address.postalCode, address.recipientName,
            address.recipientPhone,
        )

        /** An address typed at checkout; [savedAs] is its id when it was also saved in the address book. */
        fun of(input: AddressInput, provinceName: String, cityName: String, savedAs: AddressId?): AddressSnapshot = AddressSnapshot(
            savedAs, provinceName, cityName, input.line, input.plaque, input.unit, input.postalCode, input.recipientName, input.recipientPhone,
        )

        fun constructFromPersisted(
            addressId: AddressId?,
            provinceName: String,
            cityName: String,
            line: AddressLine,
            plaque: Plaque?,
            unit: UnitNumber?,
            postalCode: PostalCode,
            recipientName: RecipientName,
            recipientPhone: IranianMobileNumber,
        ): AddressSnapshot = AddressSnapshot(addressId, provinceName, cityName, line, plaque, unit, postalCode, recipientName, recipientPhone)
    }
}
