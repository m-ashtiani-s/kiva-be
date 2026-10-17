package com.kiva.application.usecase.audit

import com.kiva.application.port.AuditChange
import com.kiva.common.valueobject.Money
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/** How a change log row's `changes` are written: only what changed, every kind of value the same way everywhere. */
class AuditDiffTest {

    private enum class Status { DRAFT, ACTIVE }

    private fun money(toman: Long) = Money.makeNew(toman = toman).getOrNull() ?: error("bad money")

    @Test
    fun `only the fields whose value differs are kept, in the order they were named`() {
        val changes = AuditDiff.build {
            field("name", "ماهک", "ماهک")
            field("price", money(1_890_000), money(1_790_000))
            field("returnable", true, true)
            field("status", Status.DRAFT, Status.ACTIVE)
        }

        assertEquals(listOf(AuditChange("price", "1890000", "1790000"), AuditChange("status", "DRAFT", "ACTIVE")), changes)
    }

    @Test
    fun `nothing changed - no changes at all`() {
        assertEquals(emptyList(), AuditDiff.build { field("name", "a", "a"); longText("description", "x", "x"); list("roles", listOf("A"), listOf("A")) })
    }

    @Test
    fun `money has no separators, booleans and numbers are plain, an instant is ISO in UTC, null stays null`() {
        val changes = AuditDiff.build {
            field("compareAtPrice", null, money(2_000_000))
            field("reservable", false, true)
            field("sortOrder", 3, 10)
            field("publishAt", Instant.parse("2036-03-01T09:00:00Z"), null)
        }

        assertEquals(
            listOf(
                AuditChange("compareAtPrice", null, "2000000"),
                AuditChange("reservable", "false", "true"),
                AuditChange("sortOrder", "3", "10"),
                AuditChange("publishAt", "2036-03-01T09:00:00Z", null),
            ),
            changes,
        )
    }

    @Test
    fun `long text keeps only the fact that it changed - never the text`() {
        val changes = AuditDiff.build { longText("description", "<p>قدیمی</p>", "<p>تازه</p>") }

        assertEquals(listOf(AuditChange("description", null, null)), changes)
    }

    @Test
    fun `a hidden value keeps only the fact that it changed`() {
        assertEquals(listOf(AuditChange("bankReference", null, null)), AuditDiff.build { hidden("bankReference", null, "123456789") })
        assertEquals(emptyList(), AuditDiff.build { hidden("bankReference", "1", "1") })
    }

    @Test
    fun `a short text over 200 characters is kept as a change without its text`() {
        assertEquals(listOf(AuditChange("label", null, null)), AuditDiff.build { field("label", "a", "ب".repeat(201)) })
        assertEquals(listOf(AuditChange("label", "a", "ب".repeat(200))), AuditDiff.build { field("label", "a", "ب".repeat(200)) })
    }

    @Test
    fun `a list is compact JSON in the order given`() {
        val changes = AuditDiff.build { list("roles", listOf("CUSTOMER"), listOf("CUSTOMER", "OPERATOR")) }

        assertEquals(listOf(AuditChange("roles", """["CUSTOMER"]""", """["CUSTOMER","OPERATOR"]""")), changes)
    }

    @Test
    fun `a small object is compact JSON with its keys sorted, so the same object is the same text`() {
        val changes = AuditDiff.build {
            json("seo", mapOf("title" to "کیف", "noIndex" to false), mapOf("noIndex" to true, "title" to "کیف \"دوشی\""))
        }

        assertEquals(listOf(AuditChange("seo", """{"noIndex":false,"title":"کیف"}""", """{"noIndex":true,"title":"کیف \"دوشی\""}""")), changes)
        assertEquals(emptyList(), AuditDiff.build { json("seo", mapOf("b" to 1, "a" to 2), mapOf("a" to 2, "b" to 1)) })
    }
}
