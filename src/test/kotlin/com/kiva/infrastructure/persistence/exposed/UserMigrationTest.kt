package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The constraints of V8__users.sql: the database itself refuses a bad user row, whatever wrote it. Rows are written
 * with plain SQL, past every value object. Every write here is rolled back.
 */
class UserMigrationTest {

    private val db = TestDatabase.database

    /** An INSERT of a valid minimal row, with [columns] (name to SQL value) added or replacing its values. */
    private fun insertSql(vararg columns: Pair<String, String>): String {
        val all = mapOf("phone" to "'09900000001'", "\"createdOn\"" to "now()") + columns
        return "INSERT INTO user_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})"
    }

    private fun assertAccepted(vararg columns: Pair<String, String>) = transaction(db) {
        exec(insertSql(*columns))
        rollback()
    }

    private fun assertRejected(vararg columns: Pair<String, String>) = transaction(db) {
        assertFailsWith<ExposedSQLException> { exec(insertSql(*columns)) }
        rollback()
    }

    @Test
    fun `a full valid row is accepted (and rolled back)`() = assertAccepted(
        "\"firstName\"" to "'سارا'",
        "\"lastName\"" to "'عبدالله‌زاده'",
        "email" to "'sara.m+shop@mail.example.ir'",
        "\"birthDate\"" to "'1996-09-10'",
        "gender" to "'FEMALE'",
        "\"defaultMessenger\"" to "'TELEGRAM'",
        "\"defaultMessengerPhone\"" to "'09351112233'",
        "\"marketingSmsOptIn\"" to "true",
        "roles" to "'{CUSTOMER,ADMIN,SUPPORT}'",
    )

    @Test
    fun `a row with only phone and createdOn gets the defaults of a new account`() {
        val defaults = transaction(db) {
            exec(insertSql())
            val row = exec("SELECT roles::text, \"marketingSmsOptIn\", gender, version FROM user_tbl WHERE phone = '09900000001'") {
                it.next()
                listOf(it.getString(1), it.getBoolean(2), it.getString(3), it.getLong(4))
            }
            rollback()
            row
        }

        assertEquals(listOf("{CUSTOMER}", false, "UNSPECIFIED", 0L), defaults)
    }

    @Test
    fun `a phone not in the stored form is refused`() = assertRejected("phone" to "'9121234567'")

    @Test
    fun `a phone can have only one account`() = transaction(db) {
        exec(insertSql())
        assertFailsWith<ExposedSQLException> { exec(insertSql()) }
        rollback()
    }

    @Test
    fun `roles without CUSTOMER are refused`() = assertRejected("roles" to "'{ADMIN}'")

    @Test
    fun `a role outside the contract is refused`() = assertRejected("roles" to "'{CUSTOMER,OWNER}'")

    @Test
    fun `a messenger number without a messenger is refused`() = assertRejected("\"defaultMessengerPhone\"" to "'09351112233'")

    @Test
    fun `a messenger outside the contract is refused`() = assertRejected("\"defaultMessenger\"" to "'WHATSAPP'")

    @Test
    fun `a messenger number not in the stored form is refused`() =
        assertRejected("\"defaultMessenger\"" to "'BALE'", "\"defaultMessengerPhone\"" to "'+989351112233'")

    @Test
    fun `an email in capitals or without a domain is refused`() {
        assertRejected("email" to "'Sara@x.com'")
        assertRejected("email" to "'sara@x'")
    }

    @Test
    fun `a blank name, or one with angle brackets or a control character, is refused`() {
        assertRejected("\"firstName\"" to "'   '")
        assertRejected("\"lastName\"" to "'<b>'")
        assertRejected("\"firstName\"" to "E'سا\\u0007را'")
    }

    @Test
    fun `a name over 50 characters is refused`() = assertRejected("\"firstName\"" to "'${"س".repeat(51)}'")

    @Test
    fun `a birth date before 1900 is refused`() = assertRejected("\"birthDate\"" to "'1899-12-31'")

    @Test
    fun `a gender outside the contract is refused`() = assertRejected("gender" to "'OTHER'")

    @Test
    fun `a negative version is refused`() = assertRejected("version" to "-1")
}
