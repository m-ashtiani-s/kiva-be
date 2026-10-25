package com.kiva.infrastructure.persistence.exposed.tbl

import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Why customers left: text and time, nobody's id. Created by V39__account_deletion.sql; keep both in step. */
object AccountDeletionReasonTbl : LongIdTable(name = "accountdeletionreason_tbl", columnName = "pId") {
    val reason = varchar("reason", 300)
    val createdOn = timestampWithTimeZone("createdOn")
}
