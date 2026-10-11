package com.kiva.domain.aggregate.session.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.session.model.valueobject.SessionId
import com.kiva.domain.aggregate.session.model.valueobject.SessionKind
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.domain.aggregate.user.model.valueobject.Roles
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthSessionTest {

    private val now = Instant.parse("2026-10-09T08:30:00Z")
    private val user = UserId.constructFromPersisted(1024)

    private fun start(roles: Roles = Roles.CUSTOMER_ONLY) =
        AuthSession.start(SessionId.constructFromPersisted(88), user, roles, now, SessionDevice.of("Chrome on Android", "Mozilla/5.0", "203.0.113.9"))

    @Test
    fun `a customer's session lasts 30 days unused and 180 days at most`() {
        val session = start()

        assertEquals(SessionKind.CUSTOMER, session.kind)
        assertEquals(now.plus(Duration.ofDays(30)), session.idleExpiresOn)
        assertEquals(now.plus(Duration.ofDays(180)), session.absoluteExpiresOn)
        assertEquals(now, session.createdOn)
        assertEquals(now, session.lastUsedOn)
        assertNull(session.revokedOn)
    }

    @Test
    fun `any panel role makes a panel session, 12 hours unused and 7 days at most`() {
        val session = start(Roles.of(listOf(Role.CATALOG_MANAGER)))

        assertEquals(SessionKind.PANEL, session.kind)
        assertEquals(now.plus(Duration.ofHours(12)), session.idleExpiresOn)
        assertEquals(now.plus(Duration.ofDays(7)), session.absoluteExpiresOn)
    }

    @Test
    fun `a session is active until its idle end`() {
        val session = start()

        assertTrue(session.isActiveAt(now.plus(Duration.ofDays(30)).minusSeconds(1)))
        assertFalse(session.isActiveAt(now.plus(Duration.ofDays(30))))
    }

    @Test
    fun `a revoked session is not active, and a revoke always has its reason`() {
        val revoked = AuthSession.constructFromPersisted(
            SessionId.constructFromPersisted(88), user, SessionKind.CUSTOMER, now, now, now.plus(Duration.ofDays(30)),
            now.plus(Duration.ofDays(180)), revokedOn = now.plusSeconds(60), revokeReason = RevokeReason.LOGOUT, device = SessionDevice.UNKNOWN,
        )
        assertFalse(revoked.isActiveAt(now.plusSeconds(61)))

        assertFailsWith<IllegalArgumentException> {
            AuthSession.constructFromPersisted(
                SessionId.constructFromPersisted(88), user, SessionKind.CUSTOMER, now, now, now.plus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(180)), revokedOn = now, revokeReason = null, device = SessionDevice.UNKNOWN,
            )
        }
    }

    @Test
    fun `an idle end after the absolute end is a bug`() {
        assertFailsWith<IllegalArgumentException> {
            AuthSession.constructFromPersisted(
                SessionId.constructFromPersisted(88), user, SessionKind.CUSTOMER, now, now, now.plus(Duration.ofDays(200)),
                now.plus(Duration.ofDays(180)), revokedOn = null, revokeReason = null, device = SessionDevice.UNKNOWN,
            )
        }
    }

    @Test
    fun `the system never logs in`() {
        assertFailsWith<IllegalArgumentException> {
            AuthSession.start(SessionId.constructFromPersisted(88), UserId.SYSTEM, Roles.CUSTOMER_ONLY, now, SessionDevice.UNKNOWN)
        }
    }

    private val hash = TokenHash(ByteArray(32) { 3 })

    private fun current(session: AuthSession) = RefreshToken(session.id, hash, session.createdOn, rotatedOn = null)

    private fun rotated(session: AuthSession, on: Instant) = RefreshToken(session.id, hash, session.createdOn, rotatedOn = on)

    @Test
    fun `the current token is rotated, and the session's idle end moves to 30 days from now`() {
        val session = start()
        val later = now.plus(Duration.ofDays(10))

        val refresh = session.refreshWith(current(session), later)

        val rotated = (refresh as AuthSession.Refresh.Rotate).session
        assertEquals(later, rotated.lastUsedOn)
        assertEquals(later.plus(Duration.ofDays(30)), rotated.idleExpiresOn)
        assertEquals(session.absoluteExpiresOn, rotated.absoluteExpiresOn)
    }

    @Test
    fun `the idle end never goes past the absolute end`() {
        // In use until day 160, so still active on day 170.
        val session = AuthSession.constructFromPersisted(
            SessionId.constructFromPersisted(88), user, SessionKind.CUSTOMER, now, now.plus(Duration.ofDays(160)),
            now.plus(Duration.ofDays(180)), now.plus(Duration.ofDays(180)), revokedOn = null, revokeReason = null, device = SessionDevice.UNKNOWN,
        )

        val rotated = (session.refreshWith(current(session), now.plus(Duration.ofDays(170))) as AuthSession.Refresh.Rotate).session

        assertEquals(now.plus(Duration.ofDays(180)), rotated.idleExpiresOn)
    }

    @Test
    fun `a token rotated up to 30 seconds ago is a second tab, rotated longer ago it was copied`() {
        val session = start()
        val rotatedOn = now.plusSeconds(60)

        assertEquals(AuthSession.Refresh.WithinGrace, session.refreshWith(rotated(session, rotatedOn), rotatedOn.plusSeconds(30)))

        val reused = session.refreshWith(rotated(session, rotatedOn), rotatedOn.plusSeconds(31))
        reused as AuthSession.Refresh.Reused
        assertEquals(Duration.ofSeconds(31), reused.sinceRotation)
        assertEquals(RevokeReason.REUSE_DETECTED, reused.session.revokeReason)
        assertFalse(reused.session.isActiveAt(rotatedOn.plusSeconds(31)))
    }

    @Test
    fun `a revoked session or one out of time refuses any token`() {
        val session = start()

        assertEquals(AuthSession.Refresh.Refused, session.revoke(RevokeReason.LOGOUT, now).refreshWith(current(session), now.plusSeconds(1)))
        assertEquals(AuthSession.Refresh.Refused, session.refreshWith(current(session), now.plus(Duration.ofDays(30))))
    }

    @Test
    fun `a session revoked once keeps its first reason`() {
        val revoked = start().revoke(RevokeReason.REUSE_DETECTED, now).revoke(RevokeReason.LOGOUT, now.plusSeconds(5))

        assertEquals(RevokeReason.REUSE_DETECTED, revoked.revokeReason)
        assertEquals(now, revoked.revokedOn)
    }

    @Test
    fun `a token hash is 32 bytes, compared by its bytes, and never shown`() {
        assertEquals(TokenHash(ByteArray(32) { 1 }), TokenHash(ByteArray(32) { 1 }))
        assertNotEquals(TokenHash(ByteArray(32) { 1 }), TokenHash(ByteArray(32) { 2 }))
        assertFailsWith<IllegalArgumentException> { TokenHash(ByteArray(31)) }
        assertEquals("TokenHash(32 bytes)", TokenHash(ByteArray(32)).toString())
    }
}
