package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * How to look after a material (با دستمال نرم و کمی مرطوب تمیز شود): at most [MAX_ITEMS] lines, each trimmed and 1 to
 * [MAX_LENGTH] characters, in the order given.
 */
@ConsistentCopyVisibility
data class CareInstructions private constructor(val items: List<String>) {

    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(items.size <= MAX_ITEMS && items.all { it.isNotBlank() && it.length <= MAX_LENGTH }) { "Invalid CareInstructions" }
    }

    companion object {
        const val MAX_ITEMS = 10
        const val MAX_LENGTH = 200

        val NONE = CareInstructions(emptyList())

        /** The list as the form sent it; null is none. A blank line, a line too long or too many lines is one failure. */
        fun makeNew(raw: List<String>?): Result<CareInstructions, Failure> {
            val lines = raw.orEmpty().map { it.normalizePersianLetters() }
            return if (lines.size > MAX_ITEMS || lines.any { it.isEmpty() || it.length > MAX_LENGTH }) Result.failure(Failure.Invalid)
            else Result.success(CareInstructions(lines))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(items: List<String>): CareInstructions = CareInstructions(items.toList())
    }

    // Always the `careInstructions` field of the contract's MaterialUpsertRequest.
    sealed class Failure(code: String) :
        ResultFailure(code, FailureKind.INVALID, field = "careInstructions", meta = mapOf("maxItems" to MAX_ITEMS, "maxLength" to MAX_LENGTH)) {
        data object Invalid : Failure("CARE_INSTRUCTIONS_INVALID")
    }
}
