package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The text of the product page's «توضیحات» tab. Optional.
 *
 * The contract sends it as HTML that the server has made safe (only a few tags allowed). Until the admin panel
 * exists only seed text reaches it, so it is kept as given; cleaning HTML comes with admin input.
 */
@JvmInline
value class ProductDescription private constructor(val value: String) {

    // Second wall: an empty description is stored as no description, so a blank stored value is a bug.
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid ProductDescription (length ${value.length})" }
    }

    companion object {
        const val MAX_LENGTH = 10_000

        /** Optional text: missing or blank means "none" (null), not an error. Spaces around it are trimmed. */
        fun makeNew(raw: String?): Result<ProductDescription?, Failure> {
            val trimmed = raw?.trim().orEmpty()
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
