package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMessagesTest {

    /**
     * The `ErrorCode` enum read straight from the API contract in this repo (api/kiva-openapi.yml),
     * so a code added to the contract can't be forgotten here. Tests run with the project root as working directory.
     */
    private val contractCodes: List<String> by lazy {
        val lines = File("api/kiva-openapi.yml").readLines()
        val schema = lines.indexOfFirst { it.trimEnd() == "    ErrorCode:" }
        require(schema >= 0) { "ErrorCode schema not found in api/kiva-openapi.yml" }
        lines.drop(schema + 1)
            .dropWhile { it.trim() != "enum:" }
            .drop(1)
            .takeWhile { it.trimStart().startsWith("- ") }
            .map { it.trim().removePrefix("- ").trim() }
    }

    @Test
    fun `every error code of the contract has a Persian message`() {
        val missing = contractCodes - ErrorMessages.knownCodes
        assertEquals(emptyList(), missing, "Add a message to ErrorMessages for: $missing")
        assertTrue(contractCodes.size > 50, "Read only ${contractCodes.size} codes from the contract; the parser is broken")
    }

    @Test
    fun `placeholders are filled from meta with Persian digits and separators`() {
        assertEquals(
            "این کد برای خرید بالای ۲٬۰۰۰٬۰۰۰ تومان فعاله",
            ErrorMessages.messageFor("DISCOUNT_MIN_SUBTOTAL_NOT_MET", FailureKind.BUSINESS_RULE, mapOf("minSubtotal" to 2_000_000L)),
        )
        assertEquals(
            "فقط ۳ عدد از این کالا موجوده",
            ErrorMessages.messageFor("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE, mapOf("availableQuantity" to 3)),
        )
    }

    @Test
    fun `a missing placeholder value falls back to the general text of the kind, never a half sentence`() {
        assertEquals("این کار الان ممکن نیست", ErrorMessages.messageFor("QUANTITY_EXCEEDS_STOCK", FailureKind.BUSINESS_RULE))
    }

    @Test
    fun `an unknown code uses the text of its kind`() {
        assertEquals("این مقدار درست نیست", ErrorMessages.messageFor("SOMETHING_NEW", FailureKind.INVALID))
        assertEquals("چیزی که دنبالش بودی پیدا نشد", ErrorMessages.messageFor("SOMETHING_NEW", FailureKind.NOT_FOUND))
    }

    @Test
    fun `every kind has a general text`() {
        FailureKind.entries.forEach { kind ->
            assertTrue(ErrorMessages.messageFor("NO_SUCH_CODE", kind).isNotBlank(), "no text for $kind")
        }
    }

    @Test
    fun `Persian number formatting`() {
        assertEquals("۰", 0L.toPersianNumber())
        assertEquals("۹۹۹", 999L.toPersianNumber())
        assertEquals("۱٬۰۰۰", 1_000L.toPersianNumber())
        assertEquals("۱٬۸۹۰٬۰۰۰", 1_890_000L.toPersianNumber())
        assertEquals("-۱۲٬۳۴۵", (-12_345L).toPersianNumber())
    }
}
