package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import java.net.URI

/**
 * One photo or video of one color of a product. A color holds its media as an ordered list: the first one is the
 * card photo, the order is the gallery order.
 *
 * Kept minimal until uploads exist: where the file is, what kind, which side, and the alt text. Sizes, thumbnails
 * and the rest of the contract's MediaAsset are made by the upload step.
 */
@ConsistentCopyVisibility
data class ProductMedia private constructor(
    val type: MediaType,
    val url: String,
    val view: MediaView?,
    val alt: String?,
) {
    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(isValidUrl(url)) { "Invalid media url '$url'" }
        require(alt == null || (alt.isNotBlank() && alt.length <= MAX_ALT_LENGTH)) { "Invalid media alt '$alt'" }
    }

    companion object {
        const val MAX_URL_LENGTH = 500
        const val MAX_ALT_LENGTH = 200

        /** The url must be a full http(s) address. Alt text is trimmed and blank means none. All problems at once. */
        fun makeNew(type: MediaType, url: String, view: MediaView?, alt: String?): Result<ProductMedia, Failure> {
            val trimmedUrl = url.trim()
            val altText = alt?.trim()?.ifEmpty { null }

            val problems = buildList {
                if (!isValidUrl(trimmedUrl)) add(Failure.UrlInvalid)
                if (altText != null && altText.length > MAX_ALT_LENGTH) add(Failure.AltTooLong)
            }
            if (problems.isNotEmpty()) return Result.failure(Failure.Invalid(problems))

            return Result.success(ProductMedia(type, trimmedUrl, view, altText))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(type: MediaType, url: String, view: MediaView?, alt: String?): ProductMedia =
            ProductMedia(type, url, view, alt)

        private fun isValidUrl(url: String): Boolean {
            if (url.isEmpty() || url.length > MAX_URL_LENGTH) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
        }
    }

    // The whole failure is one item of a `media` list; each problem inside names its own field (url, alt).
    sealed class Failure(
        code: String,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, FailureKind.INVALID, field = field, causes = causes, meta = meta) {
        data class Invalid(val problems: List<Failure>) : Failure("MEDIA_INVALID", causes = problems)
        data object UrlInvalid : Failure("MEDIA_URL_INVALID", "url", meta = mapOf("maxLength" to MAX_URL_LENGTH))
        data object AltTooLong : Failure("MEDIA_ALT_TOO_LONG", "alt", meta = mapOf("maxLength" to MAX_ALT_LENGTH))
    }
}
