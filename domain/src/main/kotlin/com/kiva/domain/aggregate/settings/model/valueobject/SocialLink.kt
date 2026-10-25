package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/** Where the shop is on social media. Values from the contract's SocialChannel. */
enum class SocialChannel {
    RUBIKA,
    TELEGRAM,
    BALE,
    INSTAGRAM,
}

/**
 * One of the shop's pages on social media (the contract's SocialLink): its [channel], the [name] shown («تلگرام»), the
 * https [url] of the page, and the [handle] people search for (`@kiva_shop`), if any.
 */
@ConsistentCopyVisibility
data class SocialLink private constructor(val channel: SocialChannel, val name: String, val url: String, val handle: String?) {

    init {
        require(isValid(name, url, handle)) { "Invalid SocialLink $channel '$name'" }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40
        const val MAX_URL_LENGTH = 300
        const val MAX_HANDLE_LENGTH = 50

        private fun isValid(name: String, url: String, handle: String?): Boolean =
            name.isNotBlank() && name.length <= MAX_NAME_LENGTH &&
                url.startsWith("https://") && url.length > "https://".length && url.length <= MAX_URL_LENGTH && url.none { it.isWhitespace() } &&
                (handle == null || (handle.isNotBlank() && handle.length <= MAX_HANDLE_LENGTH))

        /**
         * The panel's link as it came. Anything wrong with it is one answer, SOCIAL_LINK_INVALID, which the caller puts
         * under the link's index. A blank [handle] is no handle.
         */
        fun makeNew(channel: String?, name: String?, url: String?, handle: String?): Result<SocialLink, Failure> {
            val social = SocialChannel.entries.firstOrNull { it.name == channel } ?: return Result.failure(Failure.Invalid)
            val text = name?.trim()?.normalizePersianLetters() ?: return Result.failure(Failure.Invalid)
            val page = url?.trim() ?: return Result.failure(Failure.Invalid)
            val at = handle?.trim()?.takeIf { it.isNotEmpty() }
            if (!isValid(text, page, at)) return Result.failure(Failure.Invalid)
            return Result.success(SocialLink(social, text, page, at))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(channel: SocialChannel, name: String, url: String, handle: String?): SocialLink = SocialLink(channel, name, url, handle)
    }

    // No field here: the caller puts it under the link (`social.0`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("SOCIAL_LINK_INVALID")
    }
}
