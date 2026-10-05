package com.kiva.infrastructure.common.http

import com.kiva.common.railway.FailureKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMessagesTest {

    /** The `ErrorCode` enum of the API contract (api/kiva-openapi.yml). Update both together. */
    private val contractCodes = listOf(
        "VALIDATION_ERROR", "UNAUTHORIZED", "TOKEN_EXPIRED", "REFRESH_TOKEN_INVALID", "FORBIDDEN", "NOT_FOUND",
        "RATE_LIMITED", "INTERNAL_ERROR", "PHONE_INVALID", "OTP_INVALID", "OTP_EXPIRED", "OTP_TOO_MANY_ATTEMPTS",
        "OTP_RESEND_TOO_SOON", "PHONE_ALREADY_REGISTERED", "PRODUCT_NOT_FOUND", "VARIANT_NOT_FOUND", "PRODUCT_UNAVAILABLE",
        "OUT_OF_STOCK", "QUANTITY_EXCEEDS_STOCK", "QUANTITY_EXCEEDS_LIMIT", "CART_NOT_FOUND", "CART_EMPTY",
        "CART_ITEM_NOT_FOUND", "DISCOUNT_CODE_INVALID", "DISCOUNT_CODE_EXPIRED", "DISCOUNT_CODE_NOT_STARTED",
        "DISCOUNT_MIN_SUBTOTAL_NOT_MET", "DISCOUNT_FIRST_ORDER_ONLY", "DISCOUNT_USAGE_LIMIT_REACHED",
        "DISCOUNT_LOGIN_REQUIRED", "DISCOUNT_NOT_APPLICABLE", "ADDRESS_NOT_FOUND", "ADDRESS_LIMIT_REACHED",
        "CITY_NOT_IN_PROVINCE", "SHIPPING_METHOD_UNAVAILABLE", "MESSENGER_REQUIRED", "MESSENGER_PHONE_INVALID",
        "TERMS_NOT_ACCEPTED", "PRICE_CHANGED", "IDEMPOTENCY_KEY_REUSED", "ORDER_NOT_FOUND", "ORDER_NOT_CANCELLABLE",
        "ORDER_NOT_RETURNABLE", "RETURN_WINDOW_EXPIRED", "ORDER_ALREADY_PAID", "CHANGE_REQUEST_NOT_ALLOWED",
        "INVALID_STATUS_TRANSITION", "PAYMENT_NOT_FOUND", "PAYMENT_FAILED", "PAYMENT_EXPIRED",
        "PAYMENT_GATEWAY_UNAVAILABLE", "TRACKING_NOT_FOUND", "REVIEW_ALREADY_EXISTS", "REVIEW_NOT_EDITABLE",
        "STOCK_ALERT_EXISTS", "PRODUCT_IN_STOCK", "WISHLIST_LIMIT_REACHED", "UPLOAD_TOO_LARGE",
        "UPLOAD_TYPE_NOT_ALLOWED", "DUPLICATE_SLUG", "DUPLICATE_SKU",
    )

    @Test
    fun `every error code of the contract has a Persian message`() {
        val missing = contractCodes - ErrorMessages.knownCodes
        assertEquals(emptyList(), missing, "Add a message to ErrorMessages for: $missing")
        assertEquals(61, contractCodes.size)
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
