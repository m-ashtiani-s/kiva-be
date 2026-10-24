package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.user.model.valueobject.Gender
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.domain.aggregate.user.model.valueobject.Role
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ComparisonOp
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/**
 * User accounts. Created by V8__users.sql and grown by V39__account_deletion.sql (Exposed never creates tables); keep
 * them in step. A deleted account is a tombstone: [deletedOn] set and [phone] null.
 */
object UserTbl : LongIdTable(name = "user_tbl", columnName = "pId") {
    val phone = varchar("phone", 11).uniqueIndex().nullable()
    val firstName = varchar("firstName", 50).nullable()
    val lastName = varchar("lastName", 50).nullable()
    val email = varchar("email", 254).nullable()
    val birthDate = date("birthDate").nullable()
    val gender = enumerationByName<Gender>("gender", 11)
    val defaultMessenger = enumerationByName<MessengerChannel>("defaultMessenger", 10).nullable()
    val defaultMessengerPhone = varchar("defaultMessengerPhone", 11).nullable()
    val marketingSmsOptIn = bool("marketingSmsOptIn")
    /** Role names, CUSTOMER always among them. */
    val roles = array<String>("roles")
    val createdOn = timestampWithTimeZone("createdOn")
    val version = long("version")
    val deletedOn = timestampWithTimeZone("deletedOn").nullable()

    /** The unique key on [phone] as V8 named it; the database names it when it refuses a save. */
    const val PHONE_KEY = "user_tbl_phone_key"

    /**
     * The sequence behind [id] (the IDENTITY column), so an id can be taken before the row is written. The column name
     * is taken as written, case included, so it goes without the quotes it needs in SQL.
     */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('user_tbl', 'pId')"

    /** The account isn't deleted. */
    fun notDeleted(): Op<Boolean> = deletedOn.isNull()

    /** The user has [role]: Postgres `@>` on [roles]. */
    fun hasRole(role: Role): Op<Boolean> = Contains(roles, listOf(role.name))

    /** Postgres `@>`: the array column holds every one of [values]. */
    private class Contains(column: Column<List<String>>, values: List<String>) :
        ComparisonOp(column, QueryParameter(values, column.columnType), "@>")
}
