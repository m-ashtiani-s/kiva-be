package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** The panel's notes on orders, only ever added. Created by V27__order_notes.sql (Exposed never creates tables). */
object OrderNoteTbl : LongIdTable(name = "ordernote_tbl", columnName = "pId") {
    val orderId = long("orderId")
    val text = varchar("text", 1000)
    val authorId = long("authorId")
    val createdOn = timestampWithTimeZone("createdOn")
}
