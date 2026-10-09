package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * The constraints of V9__otp_and_security_events.sql: the database itself refuses a bad code row and any change to the
 * security log, whatever wrote it. Rows are written with plain SQL, past the domain. Every write here is rolled back.
 */
class OtpMigrationTest {

    private val db = TestDatabase.database

    /** An INSERT of a valid open LOGIN code, with [columns] (name to SQL value) added or replacing its values. */
    private fun codeSql(vararg columns: Pair<String, String>): String {
        val all = mapOf(
            "purpose" to "'LOGIN'",
            "phone" to "'09900000001'",
            "salt" to "decode(repeat('ab', 16), 'hex')",
            "\"codeHash\"" to "decode(repeat('cd', 32), 'hex')",
            "\"createdOn\"" to "now()",
            "\"expiresOn\"" to "now() + interval '5 minutes'",
        ) + columns
        return "INSERT INTO otpchallenge_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})"
    }

    /** A user to own the codes that need one; its id comes back (a query, for Exposed, since it returns a row). */
    private fun JdbcTransaction.aUser(): Long =
        exec(
            "INSERT INTO user_tbl (phone, \"createdOn\") VALUES ('09900000009', now()) RETURNING \"pId\"",
            explicitStatementType = StatementType.SELECT,
        ) {
            it.next()
            it.getLong(1)
        } ?: error("no user id")

    private fun accepted(write: JdbcTransaction.() -> Unit) = transaction(db) {
        write()
        rollback()
    }

    private fun rejected(prepare: JdbcTransaction.() -> Unit = {}, write: JdbcTransaction.() -> Unit) = transaction(db) {
        prepare()
        assertFailsWith<ExposedSQLException> { write() }
        rollback()
    }

    @Test
    fun `a valid login code is accepted (and rolled back)`() = accepted { exec(codeSql("\"requestIp\"" to "'2001:db8::1'")) }

    @Test
    fun `a phone-change code of a user, naming the new phone, is accepted`() = accepted {
        val user = aUser()
        exec(codeSql("purpose" to "'PHONE_CHANGE_CURRENT'", "\"userId\"" to "$user", "\"authorizesPhone\"" to "'09351112233'"))
    }

    @Test
    fun `a login code with a user is refused`() = rejected(prepare = { aUser() }) {
        exec(codeSql("\"userId\"" to "(SELECT \"pId\" FROM user_tbl WHERE phone = '09900000009')"))
    }

    @Test
    fun `a code of any other purpose without a user is refused`() = rejected { exec(codeSql("purpose" to "'PHONE_CHANGE_NEW'")) }

    @Test
    fun `the current phone's code of a phone change without the new phone is refused`() {
        rejected(prepare = { aUser() }) {
            exec(codeSql("purpose" to "'PHONE_CHANGE_CURRENT'", "\"userId\"" to "(SELECT \"pId\" FROM user_tbl WHERE phone = '09900000009')"))
        }
    }

    @Test
    fun `a new phone on a login code is refused`() = rejected { exec(codeSql("\"authorizesPhone\"" to "'09351112233'")) }

    @Test
    fun `a purpose outside the list is refused`() = rejected { exec(codeSql("purpose" to "'RESET_PASSWORD'")) }

    @Test
    fun `a salt that isn't 16 bytes or a hash that isn't 32 is refused`() {
        rejected { exec(codeSql("salt" to "decode(repeat('ab', 15), 'hex')")) }
        rejected { exec(codeSql("\"codeHash\"" to "decode(repeat('cd', 31), 'hex')")) }
    }

    @Test
    fun `more than five failed attempts is refused`() = rejected { exec(codeSql("\"failedAttempts\"" to "6")) }

    @Test
    fun `a code that expires before it was made is refused`() = rejected { exec(codeSql("\"expiresOn\"" to "now() - interval '1 second'")) }

    @Test
    fun `a code both consumed and replaced is refused`() = rejected { exec(codeSql("\"consumedOn\"" to "now()", "\"replacedOn\"" to "now()")) }

    @Test
    fun `a second open code for the same phone and purpose is refused`() = rejected(prepare = { exec(codeSql()) }) { exec(codeSql()) }

    @Test
    fun `a new code next to a replaced or consumed one is accepted`() = accepted {
        exec(codeSql("\"replacedOn\"" to "now()"))
        exec(codeSql("\"consumedOn\"" to "now()"))
        exec(codeSql())
    }

    @Test
    fun `a security event can be added, but never changed or deleted`() {
        val insert = "INSERT INTO securityevent_tbl (\"occurredOn\", type, detail) VALUES (now(), 'LOGIN_SUCCEEDED', '{}')"
        accepted { exec(insert) }
        rejected(prepare = { exec(insert) }) { exec("UPDATE securityevent_tbl SET type = 'LOGOUT'") }
        rejected(prepare = { exec(insert) }) { exec("DELETE FROM securityevent_tbl") }
    }

    @Test
    fun `a security event type outside UPPER_SNAKE_CASE is refused`() =
        rejected { exec("INSERT INTO securityevent_tbl (\"occurredOn\", type) VALUES (now(), 'login')") }
}
