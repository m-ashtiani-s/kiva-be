package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Sent one-time codes. Created by V9__otp_and_security_events.sql (Exposed never creates tables); keep both in step. */
object OtpChallengeTbl : LongIdTable(name = "otpchallenge_tbl", columnName = "pId") {
    val purpose = enumerationByName<OtpPurpose>("purpose", 30)
    val phone = varchar("phone", 11)
    val userId = long("userId").nullable()
    val authorizesPhone = varchar("authorizesPhone", 11).nullable()
    val salt = binary("salt", 16)
    val codeHash = binary("codeHash", 32)
    val failedAttempts = short("failedAttempts")
    val createdOn = timestampWithTimeZone("createdOn")
    val expiresOn = timestampWithTimeZone("expiresOn")
    val consumedOn = timestampWithTimeZone("consumedOn").nullable()
    val replacedOn = timestampWithTimeZone("replacedOn").nullable()
    val requestIp = varchar("requestIp", 45).nullable()

    /** The sequence behind [id], so an id can be taken before the row is written. The column name goes without quotes. */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('otpchallenge_tbl', 'pId')"
}
