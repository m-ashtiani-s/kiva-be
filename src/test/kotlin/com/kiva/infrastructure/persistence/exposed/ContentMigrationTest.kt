package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * V31__content_basics.sql's walls, the last ones after the domain's rules: whatever writes the content (the panel, SQL by
 * hand), the database refuses what makes no sense. Every write is rolled back.
 */
class ContentMigrationTest {

    private val db = TestDatabase.database

    private fun assertRejected(sql: String) = transaction(db) {
        assertFailsWith<ExposedSQLException> { exec(sql) }
        rollback()
    }

    private fun accepted(sql: String) = transaction(db) {
        exec(sql)
        rollback()
    }

    @Test
    fun `the site content is one row with the prototype's texts - a second row is refused`() {
        val name = transaction(db) { exec("""SELECT document -> 'brand' ->> 'name' FROM sitecontent_tbl""") { rs -> rs.next(); rs.getString(1) } }
        assertEquals("کیوا", name)
        assertRejected("""INSERT INTO sitecontent_tbl (document, "updatedOn") VALUES ('{}', now())""")
    }

    @Test
    fun `a page's slug must be a slug, its title and content something`() {
        val ok = """INSERT INTO staticpage_tbl (slug, title, content, "updatedOn", "updatedBy") VALUES"""
        accepted("""$ok ('terms', 'قوانین', '<p>x</p>', now(), 0)""")
        assertRejected("""$ok ('Terms', 'قوانین', '<p>x</p>', now(), 0)""")
        assertRejected("""$ok ('123', 'قوانین', '<p>x</p>', now(), 0)""")
        assertRejected("""$ok ('terms', ' ', '<p>x</p>', now(), 0)""")
        assertRejected("""$ok ('terms', 'قوانین', '', now(), 0)""")
    }

    @Test
    fun `a FAQ group's anchor follows the pattern and is unique - a question needs its group`() {
        val group = """INSERT INTO faqgroup_tbl (anchor, name, "sortOrder") VALUES"""
        accepted("""$group ('reserve', 'رزرو', 0)""")
        assertRejected("""$group ('Reserve', 'رزرو', 0)""")
        assertRejected("""$group ('r', 'رزرو', 0)""")
        assertRejected("""$group ('reserve', 'رزرو', 0), ('reserve', 'دیگه', 1)""")
        assertRejected("""INSERT INTO faqitem_tbl ("itemId", "groupId", question, answer, "sortOrder") VALUES (1, -1, 'س', 'ج', 0)""")
    }

    @Test
    fun `an announcement ends after it starts, when both are set`() {
        val bar = """INSERT INTO announcement_tbl ("announcementId", text, "sortOrder", "startsAt", "endsAt") VALUES"""
        accepted("""$bar (1, 'ارسال رایگان', 0, now(), NULL)""")
        accepted("""$bar (1, 'ارسال رایگان', 0, now(), now() + interval '1 day')""")
        assertRejected("""$bar (1, 'ارسال رایگان', 0, now(), now())""")
        assertRejected("""$bar (1, ' ', 0, NULL, NULL)""")
    }
}
