package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.entity.RefreshToken
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.domain.aggregate.session.repository.AuthSessionRepository
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.RefreshTokenTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
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

    override fun findByIdForUpdate(id: SessionId): AuthSession? =
        AuthSessionTbl.selectAll()
            .where { AuthSessionTbl.id eq id.value }
            .forUpdate()
            .singleOrNull()
            ?.toSession()

    override fun findTokenForUpdate(hash: TokenHash): RefreshToken? =
        RefreshTokenTbl.selectAll()
            .where { RefreshTokenTbl.tokenHash eq hash.toByteArray() }
            .forUpdate()
            .singleOrNull()
            ?.let {
                RefreshToken(
                    sessionId = SessionId.constructFromPersisted(it[RefreshTokenTbl.sessionId]),
                    hash = TokenHash(it[RefreshTokenTbl.tokenHash]),
                    createdOn = it[RefreshTokenTbl.createdOn].toInstant(),
                    rotatedOn = it[RefreshTokenTbl.rotatedOn]?.toInstant(),
                )
            }

    override fun rotate(current: RefreshToken, next: TokenHash, now: Instant) {
        val rotated = RefreshTokenTbl.update({ (RefreshTokenTbl.tokenHash eq current.hash.toByteArray()) and RefreshTokenTbl.rotatedOn.isNull() }) {
            it[rotatedOn] = now.utc()
        }
        check(rotated == 1) { "Session ${current.sessionId.value}: the token to rotate is not its current one" }
        RefreshTokenTbl.insert {
            it[sessionId] = current.sessionId.value
            it[tokenHash] = next.toByteArray()
            it[createdOn] = now.utc()
        }
    }

    override fun save(session: AuthSession) {
        val updated = AuthSessionTbl.update({ AuthSessionTbl.id eq session.id.value }) {
            it[lastUsedOn] = session.lastUsedOn.utc()
            it[idleExpiresOn] = session.idleExpiresOn.utc()
            it[revokedOn] = session.revokedOn?.utc()
            it[revokeReason] = session.revokeReason
        }
        check(updated == 1) { "Session ${session.id.value} is not stored, so it can't be saved" }
    }

    override fun revokeAllOf(userId: UserId, reason: RevokeReason, now: Instant): Int =
        AuthSessionTbl.update({ (AuthSessionTbl.userId eq userId.value) and AuthSessionTbl.revokedOn.isNull() }) {
            it[revokedOn] = now.utc()
            it[revokeReason] = reason
        }

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
