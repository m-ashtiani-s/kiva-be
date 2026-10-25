package com.kiva.common.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Where a link a panel form gives goes (an announcement's, later a banner's): a whole `https://` address, or a path of
 * this site (`/faq#reserve`, `/products?onSale=true`), at most [MAX_LENGTH]. Never `//host` or `/\host`, which a browser
 * reads as another site, nor a space or a control character (roadmap phase 5, decision 11).
 */
@JvmInline
value class SiteLink private constructor(val value: String) {

    init {
        require(isValid(value)) { "Invalid SiteLink '$value'" }
    }

    companion object {
        const val MAX_LENGTH = 300

        private fun isValid(text: String): Boolean {
            if (text.isEmpty() || text.length > MAX_LENGTH || text.any { it.isWhitespace() || it.isISOControl() }) return false
            val path = text.startsWith("/") && !text.startsWith("//") && !text.startsWith("/\\")
            return path || (text.startsWith("https://") && text.length > "https://".length)
        }

        /** The link as the form sent it, trimmed. */
        fun makeNew(raw: String): Result<SiteLink, Failure> {
            val text = raw.trim()
            return if (isValid(text)) Result.success(SiteLink(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): SiteLink = SiteLink(value)
    }

    // No field here: the caller names it.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("URL_INVALID")
    }
}
