package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** A customer's saved products, a row each. Created by V34__wishlist.sql (Exposed never creates tables); keep both in step. */
object WishlistItemTbl : LongIdTable(name = "wishlistitem_tbl", columnName = "pId") {
    val userId = long("userId")
    val productId = long("productId")
    val preferredColorKey = varchar("preferredColorKey", 31).nullable()
    val priceAtAdd = long("priceAtAdd")
    val addedOn = timestampWithTimeZone("addedOn")
}

/** The live public link of a customer's list, at most one each. Created by V34__wishlist.sql. */
object WishlistShareTbl : LongIdTable(name = "wishlistshare_tbl", columnName = "pId") {
    val userId = long("userId").uniqueIndex()
    val token = varchar("token", 35).uniqueIndex()
    val createdOn = timestampWithTimeZone("createdOn")
}
