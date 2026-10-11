package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Customers' saved addresses. Created by V13__addresses.sql (Exposed never creates tables); keep both in step. */
object AddressTbl : LongIdTable(name = "address_tbl", columnName = "pId") {
    val addressId = long("addressId").uniqueIndex()
    val userId = long("userId")
    val title = varchar("title", 30).nullable()
    val provinceId = integer("provinceId")
    val cityId = integer("cityId")
    val addressLine = varchar("addressLine", 300)
    val plaque = varchar("plaque", 10).nullable()
    val unit = varchar("unit", 10).nullable()
    val postalCode = char("postalCode", 10)
    val recipientName = varchar("recipientName", 80)
    val recipientPhone = varchar("recipientPhone", 11)
    val isSelfRecipient = bool("isSelfRecipient")
    val lat = double("lat").nullable()
    val lng = double("lng").nullable()
    val isDefault = bool("isDefault")
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")

    /** The sequence of V13 that hands out [addressId] before an address is saved. */
    const val ADDRESS_ID_SEQUENCE = "addressid_seq"
}
