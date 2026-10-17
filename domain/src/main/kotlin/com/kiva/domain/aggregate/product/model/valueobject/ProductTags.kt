package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * A product's tags (مینیمال، روزمره): at most [MAX_ITEMS], each 1 to [MAX_LENGTH] characters after trimming, in the
 * order given; the same tag twice is kept once.
 */
@JvmInline
value class ProductTags private constructor(val items: List<String>) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(items.size <= MAX_ITEMS && items.all { it.isNotBlank() && it.length <= MAX_LENGTH }) { "Invalid ProductTags $items" }
    }

    companion object {
        const val MAX_ITEMS = 20
        const val MAX_LENGTH = 30

        val NONE = ProductTags(emptyList())

        /** Null is no tags. Too many is one failure on `tags`; each bad tag one on `tags.{i}`. */
        fun makeNew(raw: List<String>?): Result<ProductTags, ResultFailure> {
            val tags = raw.orEmpty().map { it.normalizePersianLetters() }
            if (tags.size > MAX_ITEMS) return Result.failure(Failure.TooMany)
            val bad = tags.withIndex().filter { it.value.isEmpty() || it.value.length > MAX_LENGTH }.map { Failure.Invalid("tags.${it.index}") }
            if (bad.isNotEmpty()) return Result.failure(InputProblems(bad))
            return Result.success(ProductTags(tags.distinct()))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(items: List<String>): ProductTags = ProductTags(items.toList())
    }

    sealed class Failure(code: String, field: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object TooMany : Failure("TAGS_TOO_MANY", "tags", mapOf("maxItems" to MAX_ITEMS))
        data class Invalid(val at: String) : Failure("TAG_INVALID", at, mapOf("maxLength" to MAX_LENGTH))
    }
}
