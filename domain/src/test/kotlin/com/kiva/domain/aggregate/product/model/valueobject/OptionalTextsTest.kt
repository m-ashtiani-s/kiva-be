package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The product's optional texts share one rule: missing or blank means "none", too long is refused. */
class OptionalTextsTest {

    @Nested
    inner class MaterialDescriptionTests {
        @Test
        fun `missing or blank means none, anything else is trimmed`() {
            assertEquals(Result.success(null), MaterialDescription.makeNew(null))
            assertEquals(Result.success(null), MaterialDescription.makeNew("   "))
            assertEquals("ساتن با سگک فلزی طلایی", MaterialDescription.makeNew(" ساتن با سگک فلزی طلایی ").getOrNull()?.value)
        }

        @Test
        fun `more than 120 characters is MATERIAL_DESCRIPTION_TOO_LONG`() {
            assertEquals(Result.failure(MaterialDescription.Failure.TooLong), MaterialDescription.makeNew("س".repeat(121)))
            assertEquals("materialDescription", MaterialDescription.Failure.TooLong.field)
            assertEquals(mapOf<String, Any>("maxLength" to 120), MaterialDescription.Failure.TooLong.meta)
        }

        @Test
        fun `a blank stored value is a bug, because none is stored as no value`() {
            assertFailsWith<IllegalArgumentException> { MaterialDescription.constructFromPersisted("") }
        }
    }

    @Nested
    inner class ShortDescriptionTests {
        @Test
        fun `missing or blank means none, anything else is trimmed`() {
            assertEquals(Result.success(null), ShortDescription.makeNew(null))
            assertEquals(Result.success(null), ShortDescription.makeNew(""))
            assertEquals("مینیمال و جادار", ShortDescription.makeNew("مینیمال و جادار ").getOrNull()?.value)
        }

        @Test
        fun `more than 300 characters is SHORT_DESCRIPTION_TOO_LONG`() {
            assertEquals(300, ShortDescription.makeNew("ک".repeat(300)).getOrNull()?.value?.length)
            assertEquals(Result.failure(ShortDescription.Failure.TooLong), ShortDescription.makeNew("ک".repeat(301)))
            assertEquals("shortDescription", ShortDescription.Failure.TooLong.field)
        }
    }

    @Nested
    inner class ProductDescriptionTests {
        // Stands in for the real one (infrastructure, jsoup): it drops one known script, so the tests can see it ran.
        private val sanitizer = HtmlSanitizer { it.replace("<script>alert(1)</script>", "") }

        @Test
        fun `missing or blank means none, anything else is trimmed`() {
            val notCalled = HtmlSanitizer { error("nothing to clean") }
            assertEquals(Result.success(null), ProductDescription.makeNew(null, notCalled))
            assertEquals(Result.success(null), ProductDescription.makeNew("\n ", notCalled))
            assertEquals("<p>کیف دوشی ماهک</p>", ProductDescription.makeNew(" <p>کیف دوشی ماهک</p>\n", sanitizer).getOrNull()?.value)
        }

        @Test
        fun `what is kept is what the sanitizer left, and nothing safe left means none`() {
            assertEquals("<p>کیف</p>", ProductDescription.makeNew("<p>کیف</p><script>alert(1)</script>", sanitizer).getOrNull()?.value)
            assertEquals(Result.success(null), ProductDescription.makeNew(" <script>alert(1)</script> ", sanitizer))
        }

        @Test
        fun `more than 10000 characters is DESCRIPTION_TOO_LONG, counting what is kept`() {
            assertEquals(Result.failure(ProductDescription.Failure.TooLong), ProductDescription.makeNew("ک".repeat(10_001), sanitizer))
            assertEquals(10_000, ProductDescription.makeNew("ک".repeat(10_000) + "<script>alert(1)</script>", sanitizer).getOrNull()?.value?.length)
            assertEquals("description", ProductDescription.Failure.TooLong.field)
        }
    }
}
