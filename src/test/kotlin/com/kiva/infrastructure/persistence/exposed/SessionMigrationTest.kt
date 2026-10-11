package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The constraints of V10__sessions.sql: the database itself refuses a bad session or a second current token, whatever
 * wrote it. Rows are written with plain SQL, past the domain. Every write here is rolled back.
 */
class SessionMigrationTest {

    private val db = TestDatabase.database

    /** Runs an INSERT … RETURNING "pId" (a query, for Exposed, since it returns a row) and gives the id back. */
    private fun JdbcTransaction.insertReturningId(sql: String): Long =
        exec("$sql RETURNING \"pId\"", explicitStatementType = StatementType.SELECT) {
            it.next()
            it.getLong(1)
        } ?: error("no id")

    private fun JdbcTransaction.aUser(): Long =
        insertReturningId("INSERT INTO user_tbl (phone, \"createdOn\") VALUES ('09900000019', now())")

    /** A valid live customer session of [userId], with [columns] (name to SQL value) added or replacing its values. */
    private fun sessionSql(userId: Long, vararg columns: Pair<String, String>): String {
        val all = mapOf(
            "\"userId\"" to "$userId",
            "kind" to "'CUSTOMER'",
            "\"createdOn\"" to "now()",
            "\"lastUsedOn\"" to "now()",
            "\"idleExpiresOn\"" to "now() + interval '30 days'",
            "\"absoluteExpiresOn\"" to "now() + interval '180 days'",
        ) + columns
        return "INSERT INTO authsession_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})"
    }

    private fun tokenSql(sessionId: Long, hashByte: String = "ab", rotated: Boolean = false) =
        "INSERT INTO refreshtoken_tbl (\"sessionId\", \"tokenHash\", \"createdOn\", \"rotatedOn\") " +
            "VALUES ($sessionId, decode(repeat('$hashByte', 32), 'hex'), now(), ${if (rotated) "now()" else "NULL"})"

    private fun accepted(write: JdbcTransaction.() -> Unit) = transaction(db) {
        write()
        rollback()
    }

    private fun rejected(write: JdbcTransaction.() -> Unit) = transaction(db) {
        assertFailsWith<ExposedSQLException> { write() }
        rollback()
    }

    @Test
    fun `a session with its current token is accepted (and rolled back)`() = accepted {
        val session = insertReturningId(sessionSql(aUser(), "\"deviceName\"" to "'Chrome on Android'", "\"createdIp\"" to "'2001:db8::1'"))
        exec(tokenSql(session))
    }

    @Test
    fun `a kind other than CUSTOMER or PANEL is refused`() = rejected { exec(sessionSql(aUser(), "kind" to "'STAFF'")) }

    @Test
    fun `an idle end after the absolute end is refused`() =
        rejected { exec(sessionSql(aUser(), "\"idleExpiresOn\"" to "now() + interval '200 days'")) }

    @Test
    fun `a revoke without its reason, or a reason without a revoke, is refused`() {
        rejected { exec(sessionSql(aUser(), "\"revokedOn\"" to "now()")) }
        rejected { exec(sessionSql(aUser(), "\"revokeReason\"" to "'LOGOUT'")) }
    }

    @Test
    fun `a revoke reason outside the list is refused`() =
        rejected { exec(sessionSql(aUser(), "\"revokedOn\"" to "now()", "\"revokeReason\"" to "'EXPIRED'")) }

    @Test
    fun `a session of no user is refused`() = rejected { exec(sessionSql(Long.MAX_VALUE)) }

    @Test
    fun `a session has one current token, any number of rotated ones`() {
        accepted {
            val session = insertReturningId(sessionSql(aUser()))
            exec(tokenSql(session, "01", rotated = true))
            exec(tokenSql(session, "02", rotated = true))
            exec(tokenSql(session, "03"))
        }
        rejected {
            val session = insertReturningId(sessionSql(aUser()))
            exec(tokenSql(session, "01"))
            exec(tokenSql(session, "02"))
        }
    }

    @Test
    fun `one token hash can't be stored twice`() = rejected {
        val user = aUser()
        exec(tokenSql(insertReturningId(sessionSql(user)), "ab", rotated = true))
        exec(tokenSql(insertReturningId(sessionSql(user)), "ab", rotated = true))
    }

    @Test
    fun `a token hash that isn't 32 bytes is refused`() = rejected {
        val session = insertReturningId(sessionSql(aUser()))
        exec("INSERT INTO refreshtoken_tbl (\"sessionId\", \"tokenHash\", \"createdOn\") VALUES ($session, decode(repeat('ab', 31), 'hex'), now())")
    }
}
