package com.kiva.domain.aggregate.session.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.entity.RefreshToken
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import java.time.Instant

/**
 * Where sessions and their refresh tokens are kept. Everything runs inside the use case's transaction.
 *
 * Locks are always taken token first, then session, so two requests about one session can't wait for each other.
 */
interface AuthSessionRepository {

    /** A new id, from a sequence: never handed out twice. */
    fun nextId(): SessionId

    /** Stores a new session with its first refresh token, of which only [firstToken] (the hash) is kept. */
    fun add(session: AuthSession, firstToken: TokenHash)

    fun findById(id: SessionId): AuthSession?

    /**
     * The refresh token with this hash, locked until the transaction ends: two refreshes with one cookie run one after
     * another, and the second sees the token as the first left it (rotated a moment ago).
     */
    fun findTokenForUpdate(hash: TokenHash): RefreshToken?

    /** The session, locked until the transaction ends. */
    fun findByIdForUpdate(id: SessionId): AuthSession?

    /** Marks [current] rotated at [now] and adds [next] as the session's new current token. */
    fun rotate(current: RefreshToken, next: TokenHash, now: Instant)

    /** Saves what a session can change: its use, its idle end, its revoke. */
    fun save(session: AuthSession)

    /** Ends every session of [userId] that isn't revoked yet; how many were ended. */
    fun revokeAllOf(userId: UserId, reason: RevokeReason, now: Instant): Int
}
