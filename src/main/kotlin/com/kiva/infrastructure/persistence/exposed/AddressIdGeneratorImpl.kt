package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressIdGenerator
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/** Takes the next number from the sequence of V13__addresses.sql. Runs inside the use case's transaction. */
class AddressIdGeneratorImpl : AddressIdGenerator {
    override fun nextAddressId(): AddressId =
        TransactionManager.current().exec("SELECT nextval('${AddressTbl.ADDRESS_ID_SEQUENCE}')") { rows ->
            rows.next()
            AddressId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval('${AddressTbl.ADDRESS_ID_SEQUENCE}') returned nothing")
}
