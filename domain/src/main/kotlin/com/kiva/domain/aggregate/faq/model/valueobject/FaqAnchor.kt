package com.kiva.domain.aggregate.faq.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * A FAQ group's name in links (`/faq#reserve`), the contract's `FaqGroup.id`: 2 to 31 lower case Latin letters, digits
 * and dashes, starting with a letter (roadmap phase 5, decision 9).
 */
@JvmInline
value class FaqAnchor private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid FaqAnchor '$value'" }
    }

    companion object {
        private val FORMAT = Regex("[a-z][a-z0-9-]{1,30}")

        fun makeNew(raw: String): Result<FaqAnchor, Failure> {
            val text = raw.trim()
            return if (FORMAT.matches(text)) Result.success(FaqAnchor(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): FaqAnchor = FaqAnchor(value)
    }

    // No field here: the caller names it (the group's `id` at its index).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("FAQ_ANCHOR_INVALID")
    }
}
