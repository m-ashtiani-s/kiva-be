package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Carts. Created by V14__carts.sql (Exposed never creates tables); keep both in step. */
object CartTbl : LongIdTable(name = "cart_tbl", columnName = "pId") {
    val publicId = varchar("publicId", 15).uniqueIndex()
    val userId = long("userId").nullable().uniqueIndex()
    val guestTokenHash = binary("guestTokenHash", 32).nullable().uniqueIndex()
    val shippingMethod = enumerationByName<ShippingMethodCode>("shippingMethod", 10)
    val reserve = bool("reserve")
    /** Round 2 (step 20): the code the shopper applied; V20 lets it have '_'. */
    val discountCode = varchar("discountCode", 32).nullable()
    val createdOn = timestampWithTimeZone("createdOn")
    val updatedOn = timestampWithTimeZone("updatedOn")
}

/** The lines of carts, one per color. Created by V14__carts.sql. */
object CartItemTbl : LongIdTable(name = "cartitem_tbl", columnName = "pId") {
    val publicId = varchar("publicId", 13).uniqueIndex()
    val cartId = long("cartId").references(CartTbl.id)
    val variantId = long("variantId")
    val quantity = integer("quantity")
    val seenUnitPrice = long("seenUnitPrice")
    val seenPurchasable = bool("seenPurchasable")
    val addedOn = timestampWithTimeZone("addedOn")
}
