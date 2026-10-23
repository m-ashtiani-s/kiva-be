package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V38's walls, the last ones after the contact message's and the subscription's rules (roadmap section 18.4): each CHECK
 * refuses a row that breaks it.
 */
class ContactMigrationTest {

    private val db = TestDatabase.database

    @AfterEach
    fun removeRows() {
        transaction(db) {
            exec("""DELETE FROM contactmessage_tbl WHERE "ticketCode" LIKE 'CT-9876%'""")
            exec("""DELETE FROM newslettersubscription_tbl WHERE email LIKE '%@migration.test'""")
        }
    }

    private var messages = 0

    private fun insertMessage(columns: Map<String, String> = emptyMap()) = transaction(db) {
        val n = ++messages
        val all = linkedMapOf(
            "\"ticketCode\"" to "'CT-98760$n'", "topic" to "'OTHER'", "\"fullName\"" to "'سارا محمدی'", "phone" to "'09123334455'",
            "message" to "'سلام، یه سؤال داشتم.'", "status" to "'OPEN'", "\"createdAt\"" to "now()", "\"updatedAt\"" to "now()",
        )
        all.putAll(columns)
        exec("INSERT INTO contactmessage_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})")
    }

    private var subscriptions = 0

    private fun insertSubscription(columns: Map<String, String> = emptyMap()) = transaction(db) {
        val n = ++subscriptions
        val all = linkedMapOf(
            "email" to "'s$n@migration.test'", "status" to "'ACTIVE'", "token" to "'nl_${"a".repeat(31)}$n'", "\"subscribedAt\"" to "now()",
        )
        all.putAll(columns)
        exec("INSERT INTO newslettersubscription_tbl (${all.keys.joinToString()}) VALUES (${all.values.joinToString()})")
    }

    private fun refused(insert: () -> Unit): String? {
        val refused = assertFailsWith<ExposedSQLException> { insert() }
        return (refused.cause as? PSQLException)?.serverErrorMessage?.constraint
    }

    private val replied = mapOf("reply" to "'سلام، پیگیری کردیم.'", "\"replyChannel\"" to "'SMS'", "\"repliedAt\"" to "now()", "\"repliedBy\"" to "7")

    @Test
    fun `a new message, and one with its whole reply, go in`() {
        insertMessage()
        insertMessage(replied + mapOf("email" to "'sara@example.com'", "\"replyChannel\"" to "'EMAIL'", "\"orderCode\"" to "'KV-218340'", "\"userId\"" to "12"))
    }

    @Test
    fun `half a reply, an email reply without an email, or a code, name, mobile, text or email out of shape is refused`() {
        assertEquals("contactmessage_reply_check", refused { insertMessage(mapOf("reply" to "'سلام'")) })
        assertEquals("contactmessage_reply_check", refused { insertMessage(replied - "\"repliedBy\"") })
        assertEquals("contactmessage_email_reply_check", refused { insertMessage(replied + mapOf("\"replyChannel\"" to "'EMAIL'")) })
        assertEquals("contactmessage_tbl_ticketCode_check", refused { insertMessage(mapOf("\"ticketCode\"" to "'CT-98761'")) })
        assertEquals("contactmessage_tbl_fullName_check", refused { insertMessage(mapOf("\"fullName\"" to "'  سا '")) })
        assertEquals("contactmessage_tbl_phone_check", refused { insertMessage(mapOf("phone" to "'9123334455'")) })
        assertEquals("contactmessage_tbl_message_check", refused { insertMessage(mapOf("message" to "'سلام خوبی'")) })
        assertEquals("contactmessage_tbl_email_check", refused { insertMessage(mapOf("email" to "'Sara@example.com'")) })
        assertEquals("contactmessage_tbl_topic_check", refused { insertMessage(mapOf("topic" to "'COMPLAINT'")) })
    }

    @Test
    fun `a subscription - lower case only, a token of its shape, unsubscribed with its time and only then, one per email`() {
        insertSubscription()
        insertSubscription(mapOf("status" to "'UNSUBSCRIBED'", "\"unsubscribedAt\"" to "now()", "source" to "'footer'"))

        assertEquals("newslettersubscription_tbl_email_check", refused { insertSubscription(mapOf("email" to "'Sara@migration.test'")) })
        assertEquals("newslettersubscription_tbl_token_check", refused { insertSubscription(mapOf("token" to "'nl_short'")) })
        assertEquals("newslettersubscription_tbl_source_check", refused { insertSubscription(mapOf("source" to "'Footer Form'")) })
        assertEquals("newsletter_unsubscribed_check", refused { insertSubscription(mapOf("status" to "'UNSUBSCRIBED'")) })
        assertEquals("newsletter_unsubscribed_check", refused { insertSubscription(mapOf("\"unsubscribedAt\"" to "now()")) })
        assertEquals("newslettersubscription_tbl_email_key", refused { insertSubscription(mapOf("email" to "'s1@migration.test'")) })
    }
}
