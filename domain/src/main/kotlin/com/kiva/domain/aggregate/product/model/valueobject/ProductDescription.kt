package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The text of the product page's «توضیحات» tab. Optional.
 *
 * HTML, and the page puts it in as HTML: so a new one is always made safe first ([HtmlSanitizer]), and the contract
 * promises the client exactly that. A stored one was made safe when it was new.
 */
@JvmInline
value class ProductDescription private constructor(val value: String) {

    // Second wall: an empty description is stored as no description, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid ProductDescription (length ${value.length})" }
    }

    companion object {
        const val MAX_LENGTH = 10_000

        /**
         * Optional text: missing or blank means "none" (null), not an error. It is made safe first, so the length
         * counts what is kept, and text with nothing safe in it is none as well. Spaces around it are trimmed.
         */
        fun makeNew(raw: String?, sanitizer: HtmlSanitizer): Result<ProductDescription?, Failure> {
            val trimmed = raw?.takeIf { it.isNotBlank() }?.let(sanitizer::clean)?.trim().orEmpty()
            return when {
                trimmed.isEmpty() -> Result.success(null)
                trimmed.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(ProductDescription(trimmed))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): ProductDescription = ProductDescription(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "description", meta = meta) {
        data object TooLong : Failure("DESCRIPTION_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
