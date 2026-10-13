package com.kiva.domain.aggregate.address.repository

import com.kiva.domain.aggregate.address.model.valueobject.AddressId

/** Hands out address numbers before an address is saved (a database sequence). */
interface AddressIdGenerator {
    fun nextAddressId(): AddressId
}
