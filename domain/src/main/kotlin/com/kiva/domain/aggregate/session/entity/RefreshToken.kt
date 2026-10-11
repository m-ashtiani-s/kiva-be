package com.kiva.domain.aggregate.session.entity

import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import java.time.Instant

/**
 * One refresh token of a session, as stored: only its hash. A session's tokens are a chain: each refresh rotates the
 * current one ([rotatedOn]) and adds the next, so exactly one is current at a time.
 */
data class RefreshToken(val sessionId: SessionId, val hash: TokenHash, val createdOn: Instant, val rotatedOn: Instant?) {
    val isCurrent: Boolean get() = rotatedOn == null
}
