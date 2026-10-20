package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * An icon of the frontend's set (contract IconName: `truck`, `box`, `camera`): 1 to 30 lower case Latin letters, digits
 * and dashes. The server doesn't know the set; it only keeps the name readable.
 */
@JvmInline
value class IconName private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid IconName '$value'" }
    }

    companion object {
        private val FORMAT = Regex("[a-z0-9-]{1,30}")

        fun makeNew(raw: String): Result<IconName, Failure> {
            val text = raw.trim()
            return if (FORMAT.matches(text)) Result.success(IconName(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): IconName = IconName(value)
    }

    // No field here: the caller names it (`icon` of a shipping method; a banner's later).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("ICON_INVALID")
    }
}
