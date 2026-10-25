package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AuditLogEntryDto
import com.kiva.application.port.AuditChange
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditEntityType
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The change log's Persian sentences: every action has one, and the ones built from the changes read right. */
class AuditTextsTest {

    private fun entry(action: AuditAction, label: String? = "ماهک", changes: List<AuditChange>? = null) = AuditLogEntryDto(
        id = 1, at = Instant.parse("2036-03-06T09:00:00Z"), actorId = 7, actorFullName = null, actorPhone = "09121234567",
        action = action, entityType = AuditEntityType.PRODUCT, entityId = "12", entityLabel = label, changes = changes, ip = null,
    )

    @Test
    fun `every action has a sentence, with or without its changes`() {
        AuditAction.entries.forEach { action ->
            val sentence = AuditTexts.summary(entry(action))
            assertTrue(sentence.isNotBlank() && sentence.none { it in 'A'..'Z' }, "$action: $sentence")
        }
    }

    @Test
    fun `a price change says from what to what in toman with Persian digits`() {
        val sentence = AuditTexts.summary(entry(AuditAction.PRODUCT_PRICE_CHANGED, changes = listOf(AuditChange("price", "1890000", "1790000"))))

        assertEquals("قیمت از ۱٬۸۹۰٬۰۰۰ به ۱٬۷۹۰٬۰۰۰ تومان تغییر کرد", sentence)
    }

    @Test
    fun `a stock change says the stock on hand before and after`() {
        val sentence = AuditTexts.summary(entry(AuditAction.INVENTORY_ADJUSTED, label = "KV-1001-LILAC", changes = listOf(AuditChange("stockOnHand", "8", "13"))))

        assertEquals("موجودی «KV-1001-LILAC» از ۸ به ۱۳ رسید", sentence)
    }

    @Test
    fun `a roles change names the roles the user has now`() {
        val sentence = AuditTexts.summary(
            entry(AuditAction.USER_ROLES_CHANGED, label = "سارا محمدی", changes = listOf(AuditChange("roles", """["CUSTOMER"]""", """["CUSTOMER","OPERATOR"]"""))),
        )

        assertEquals("نقش‌های «سارا محمدی» شد: اپراتور سفارش، مشتری", sentence)
    }

    @Test
    fun `a scheduled publish names its Tehran day`() {
        val sentence = AuditTexts.summary(
            entry(AuditAction.PRODUCT_PUBLISH_SCHEDULED, changes = listOf(AuditChange("publishAt", null, "2026-10-15T21:00:00Z"))),
        )

        // 21:00 UTC on Thursday is 00:30 of Friday, 24 Mehr, in Tehran.
        assertEquals("انتشار محصول «ماهک» برای جمعه ۲۴ مهر برنامه‌ریزی شد", sentence)
    }

    @Test
    fun `a row without a label names its id`() {
        assertEquals("محصول «12» آرشیو شد", AuditTexts.summary(entry(AuditAction.PRODUCT_ARCHIVED, label = null)))
    }
}
