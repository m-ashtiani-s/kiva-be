package com.kiva.common.valueobject

import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.Result
import com.kiva.common.railway.forField
import com.kiva.common.railway.required
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SeoMetaTest {

    @Test
    fun `nothing sent is NONE - blank texts are null and noIndex is false`() {
        assertEquals(SeoMeta.NONE, SeoMeta.makeNew(null, null, null).getOrNull())
        assertEquals(SeoMeta.NONE, SeoMeta.makeNew("  ", "", null).getOrNull())
    }

    @Test
    fun `texts are trimmed and kept, noIndex as sent`() {
        val seo = SeoMeta.makeNew(" کیف دوشی | کیوا ", "کیف‌های دوشی", true).getOrNull()

        assertEquals("کیف دوشی | کیوا", seo?.title)
        assertEquals("کیف‌های دوشی", seo?.description)
        assertEquals(true, seo?.noIndex)
    }

    @Test
    fun `a title of 71 and a description of 171 are both reported, each under seo with its limit`() {
        assertEquals(70, SeoMeta.makeNew("ک".repeat(70), "ک".repeat(170), false).getOrNull()?.title?.length)

        val failure = SeoMeta.makeNew("ک".repeat(71), "ک".repeat(171), false).failureOrNull()

        assertEquals(
            listOf(Triple("seo.title", "SEO_TITLE_TOO_LONG", 70), Triple("seo.description", "SEO_DESCRIPTION_TOO_LONG", 170)),
            failure?.causes?.map { Triple(it.field, it.code, it.meta["maxLength"]) },
        )
    }

    @Test
    fun `a broken stored value is a bug`() {
        assertFailsWith<IllegalArgumentException> { SeoMeta.constructFromPersisted("ک".repeat(71), null, false) }
    }

    @Test
    fun `required gives the value - a missing one is FIELD_REQUIRED under the field the caller names`() {
        assertEquals(Result.success("x"), "x".required())

        val failure = (null as String?).required().forField("name").failureOrNull()

        assertEquals("FIELD_REQUIRED", failure?.code)
        assertEquals("name", failure?.field)
        assertEquals(FieldRequired.code, failure?.code)
    }
}
