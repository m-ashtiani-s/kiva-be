package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class AuthSessionRepositoryImpl : AuthSessionRepository {

    override fun nextId(): SessionId =
        TransactionManager.current().exec("SELECT nextval(${AuthSessionTbl.ID_SEQUENCE_OF})") { rows ->
            rows.next()
            SessionId.constructFromPersisted(rows.getLong(1))
        } ?: error("nextval of authsession_tbl's id sequence returned nothing")

    override fun add(session: AuthSession, firstToken: TokenHash) {
        AuthSessionTbl.insert {
            it[id] = session.id.value
            it[userId] = session.userId.value
            it[kind] = session.kind
            it[createdOn] = session.createdOn.utc()
            it[lastUsedOn] = session.lastUsedOn.utc()
            it[idleExpiresOn] = session.idleExpiresOn.utc()
            it[absoluteExpiresOn] = session.absoluteExpiresOn.utc()
            it[revokedOn] = session.revokedOn?.utc()
            it[revokeReason] = session.revokeReason
            it[deviceName] = session.device.name
            it[userAgent] = session.device.userAgent
            it[createdIp] = session.device.ip
        }
        RefreshTokenTbl.insert {
            it[sessionId] = session.id.value
            it[tokenHash] = firstToken.toByteArray()
            it[createdOn] = session.createdOn.utc()
        }
    }

    override fun findById(id: SessionId): AuthSession? =
        AuthSessionTbl.selectAll()
            .where { AuthSessionTbl.id eq id.value }
            .singleOrNull()
            ?.toSession()

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private fun ResultRow.toSession(): AuthSession =
        AuthSession.constructFromPersisted(
            id = SessionId.constructFromPersisted(this[AuthSessionTbl.id].value),
            userId = UserId.constructFromPersisted(this[AuthSessionTbl.userId]),
            kind = this[AuthSessionTbl.kind],
            createdOn = this[AuthSessionTbl.createdOn].toInstant(),
            lastUsedOn = this[AuthSessionTbl.lastUsedOn].toInstant(),
            idleExpiresOn = this[AuthSessionTbl.idleExpiresOn].toInstant(),
            absoluteExpiresOn = this[AuthSessionTbl.absoluteExpiresOn].toInstant(),
            revokedOn = this[AuthSessionTbl.revokedOn]?.toInstant(),
            revokeReason = this[AuthSessionTbl.revokeReason],
            device = SessionDevice.constructFromPersisted(this[AuthSessionTbl.deviceName], this[AuthSessionTbl.userAgent], this[AuthSessionTbl.createdIp]),
        )
}
