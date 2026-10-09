package com.kiva.domain.aggregate.session.repository

import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash

/** Where sessions and their refresh tokens are kept. Everything runs inside the use case's transaction. */
interface AuthSessionRepository {

    /** A new id, from a sequence: never handed out twice. */
    fun nextId(): SessionId

    /** Stores a new session with its first refresh token, of which only [firstToken] (the hash) is kept. */
    fun add(session: AuthSession, firstToken: TokenHash)

    fun findById(id: SessionId): AuthSession?
}
