package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The «چرا ماهک؟» bullets on the product page: up to 8 short lines, in the order given.
 * Blank lines are dropped (an empty row in the admin form is not a bullet), so no bullets at all is fine.
 */
@JvmInline
value class Highlights private constructor(val items: List<String>) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(items.size <= MAX_ITEMS && items.all { it.isNotBlank() && it.length <= MAX_ITEM_LENGTH }) {
            "Invalid Highlights ($items)"
        }
    }

    companion object {
        /** Both from the contract's ProductUpsertRequest. */
        const val MAX_ITEMS = 8
        const val MAX_ITEM_LENGTH = 160

        val NONE = Highlights(emptyList())

        fun makeNew(raw: List<String>): Result<Highlights, Failure> {
            val items = raw.map { it.trim() }.filter { it.isNotEmpty() }
            return when {
                items.size > MAX_ITEMS -> Result.failure(Failure.TooMany)
                items.any { it.length > MAX_ITEM_LENGTH } -> Result.failure(Failure.ItemTooLong)
                else -> Result.success(Highlights(items))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(items: List<String>): Highlights = Highlights(items.toList())
    }

    // Always the `highlights` field of the contract's ProductUpsertRequest.
    sealed class Failure(code: String, meta: Map<String, Any>) :
        ResultFailure(code, FailureKind.INVALID, field = "highlights", meta = meta) {
        data object TooMany : Failure("HIGHLIGHTS_TOO_MANY", mapOf("maxItems" to MAX_ITEMS))
        data object ItemTooLong : Failure("HIGHLIGHT_TOO_LONG", mapOf("maxLength" to MAX_ITEM_LENGTH))
    }
}
