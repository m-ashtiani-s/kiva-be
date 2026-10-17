package com.kiva.domain.aggregate.media.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * What a file was uploaded for. It decides the limits (MediaLimitsPolicy) and where the file may be attached: a
 * PRE_SHIPMENT photo never becomes a product photo.
 */
enum class MediaPurpose(val isStaffPurpose: Boolean) {
    PRODUCT(true),
    PRE_SHIPMENT(true),
    BLOG(true),
    BANNER(true),
    CATEGORY(true),
    TESTIMONIAL(true),

    /** A customer's photo of a return (phase 7). */
    RETURN_EVIDENCE(false),

    /** A customer's photo in a review (phase 7). */
    REVIEW_PHOTO(false),
    ;

    companion object {
        /** A purpose a member of staff may upload with (`POST /admin/media`); a customer's purpose is refused there. */
        fun makeNewForStaff(raw: String?): Result<MediaPurpose, Failure> {
            val purpose = entries.firstOrNull { it.name == raw?.trim() && it.isStaffPurpose }
            return purpose?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
        }
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "purpose") {
        data object Invalid : Failure("MEDIA_PURPOSE_INVALID")
    }
}
