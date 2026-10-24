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
 * Personal data: [toString] shows nothing of it. When its customer deletes their account the order is kept for the
 * books, but its address is [erased]: only the province and the city stay (roadmap phase 14, decision 2).
 *
 * @property addressId the saved address it was copied from; null for a new address that wasn't saved, and once erased.
 * @property line, postalCode, recipientName, recipientPhone there unless [isErased].
 */
class AddressSnapshot private constructor(
    val addressId: AddressId?,
    val provinceName: String,
    val cityName: String,
    val line: AddressLine?,
    val plaque: Plaque?,
    val unit: UnitNumber?,
    val postalCode: PostalCode?,
    val recipientName: RecipientName?,
    val recipientPhone: IranianMobileNumber?,
) {
    init {
        require(provinceName.isNotBlank() && cityName.isNotBlank()) { "An address snapshot needs its province and city" }
        val personal = listOf(line, postalCode, recipientName, recipientPhone)
        require(personal.all { it != null } || (personal.all { it == null } && addressId == null && plaque == null && unit == null)) {
            "An address snapshot is whole, or erased to its province and city"
        }
    }

    /** Its personal parts were erased with its customer's account: only the province and the city are left. */
    val isErased: Boolean get() = recipientName == null

    /** This address with everything personal gone (decision 2 of phase 14); erasing it again changes nothing. */
    fun erased(): AddressSnapshot = AddressSnapshot(null, provinceName, cityName, null, null, null, null, null, null)

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

        /** For values read back from storage: whole, or erased. Still checked by `init`. */
        fun constructFromPersisted(
            addressId: AddressId?,
            provinceName: String,
            cityName: String,
            line: AddressLine?,
            plaque: Plaque?,
            unit: UnitNumber?,
            postalCode: PostalCode?,
            recipientName: RecipientName?,
            recipientPhone: IranianMobileNumber?,
        ): AddressSnapshot = AddressSnapshot(addressId, provinceName, cityName, line, plaque, unit, postalCode, recipientName, recipientPhone)
    }
}
