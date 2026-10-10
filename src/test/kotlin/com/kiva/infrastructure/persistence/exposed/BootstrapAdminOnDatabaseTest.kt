package com.kiva.infrastructure.persistence.exposed

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.usecase.auth.usecase.command.BootstrapAdminUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.session.entity.AuthSession
import com.kiva.domain.aggregate.session.model.valueobject.RevokeReason
import com.kiva.domain.aggregate.session.model.valueobject.SessionDevice
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.tbl.AuthSessionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.SecurityEventTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The first admin, made on startup from KIVA_BOOTSTRAP_ADMIN_PHONE, over kiva_test. Every test starts with no ADMIN
 * at all, as a new shop does; test phones start with 0990 and are deleted after each test.
 */
class BootstrapAdminOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-02-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db
    private val users = UserRepositoryImpl()
    private val sessions = AuthSessionRepositoryImpl()

    @BeforeEach
    fun noAdminYet() {
        assertFalse(transaction(db) { users.hasAnyAdmin() }, "kiva_test has an ADMIN left over by another test")
    }

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    private fun bootstrap(phone: String): UserId? = runBlocking {
        stack.bootstrapAdmin.execute(BootstrapAdminUc.Cmd(IranianMobileNumber.constructFromPersisted(phone))).getOrNull()
    }

    private fun stored(phone: String) = transaction(db) { UserTbl.selectAll().where { UserTbl.phone eq phone }.singleOrNull() }

    private fun bootstrapEvents(userId: Long) = transaction(db) {
        SecurityEventTbl.selectAll().where { (SecurityEventTbl.type eq "ADMIN_BOOTSTRAPPED") and (SecurityEventTbl.userId eq userId) }.toList()
    }

    @Test
    fun `with no admin at all, the phone gets an account and becomes ADMIN, and it is recorded`() {
        val made = bootstrap(phone(1)) ?: error("no admin made")

        val user = stored(phone(1)) ?: error("no account")
        assertEquals(made.value, user[UserTbl.id].value)
        assertEquals(listOf("CUSTOMER", "ADMIN"), user[UserTbl.roles])
        assertEquals("true", bootstrapEvents(made.value).single()[SecurityEventTbl.detail]["newAccount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `starting again changes nothing, and the log says the setting is ignored`() {
        val made = bootstrap(phone(2)) ?: error("no admin made")
        val versionBefore = stored(phone(2))?.get(UserTbl.version)

        val infos = logged(Level.INFO) { assertNull(bootstrap(phone(2))) }

        assertEquals(versionBefore, stored(phone(2))?.get(UserTbl.version))
        assertEquals(1, bootstrapEvents(made.value).size)
        assertTrue(infos.any { "KIVA_BOOTSTRAP_ADMIN_PHONE is ignored" in it.formattedMessage }, infos.map { it.formattedMessage }.toString())
    }

    @Test
    fun `when an admin exists, the setting is ignored and makes no account`() {
        transaction(db) {
            val existing = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone(3)), clock.now).user
            users.save(existing.setPanelRoles(listOf(Role.ADMIN), by = UserId.SYSTEM).getOrNull() ?: error("refused"))
        }

        assertNull(bootstrap(phone(4)))
        assertNull(stored(phone(4)))
    }

    @Test
    fun `an existing customer keeps their roles, gets ADMIN on top, and their sessions end`() {
        val supportId = transaction(db) {
            val user = users.findOrRegister(IranianMobileNumber.constructFromPersisted(phone(5)), clock.now).user
            val support = user.setPanelRoles(listOf(Role.SUPPORT), by = UserId.SYSTEM).getOrNull() ?: error("refused")
            users.save(support)
            sessions.add(AuthSession.start(sessions.nextId(), support.id, support.roles, clock.now, SessionDevice.UNKNOWN), RandomSessionTokens().newRefreshToken().hash)
            support.id.value
        }

        bootstrap(phone(5))

        assertEquals(listOf("CUSTOMER", "ADMIN", "SUPPORT"), stored(phone(5))?.get(UserTbl.roles))
        val session = transaction(db) { AuthSessionTbl.selectAll().where { AuthSessionTbl.userId eq supportId }.single() }
        assertEquals(RevokeReason.ROLES_CHANGED, session[AuthSessionTbl.revokeReason])
        assertEquals("false", bootstrapEvents(supportId).single()[SecurityEventTbl.detail]["newAccount"]?.jsonPrimitive?.content)
    }

    private fun logged(level: Level, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(BootstrapAdminUc::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == level }
    }
}
