package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Login sessions. Created by V10__sessions.sql (Exposed never creates tables); keep both in step. */
object AuthSessionTbl : LongIdTable(name = "authsession_tbl", columnName = "pId") {
    val userId = long("userId")
    val kind = enumerationByName<SessionKind>("kind", 10)
    val createdOn = timestampWithTimeZone("createdOn")
    val lastUsedOn = timestampWithTimeZone("lastUsedOn")
    val idleExpiresOn = timestampWithTimeZone("idleExpiresOn")
    val absoluteExpiresOn = timestampWithTimeZone("absoluteExpiresOn")
    val revokedOn = timestampWithTimeZone("revokedOn").nullable()
    val revokeReason = enumerationByName<RevokeReason>("revokeReason", 20).nullable()
    val deviceName = varchar("deviceName", 100).nullable()
    val userAgent = varchar("userAgent", 255).nullable()
    val createdIp = varchar("createdIp", 45).nullable()

    /** The sequence behind [id], so an id can be taken before the row is written. The column name goes without quotes. */
    const val ID_SEQUENCE_OF = "pg_get_serial_sequence('authsession_tbl', 'pId')"
}

/** The refresh tokens of sessions, as hashes; one current per session. Created by V10__sessions.sql. */
object RefreshTokenTbl : LongIdTable(name = "refreshtoken_tbl", columnName = "pId") {
    val sessionId = long("sessionId")
    val tokenHash = binary("tokenHash", 32)
    val createdOn = timestampWithTimeZone("createdOn")
    val rotatedOn = timestampWithTimeZone("rotatedOn").nullable()
}
