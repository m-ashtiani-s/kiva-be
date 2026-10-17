package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** From which side a photo shows the bag. Values from the API contract; the Persian label is the HTTP layer's job. */
enum class MediaView {
    /** نمای جلو */
    FRONT,

    /** نمای کنار */
    SIDE,

    /** پشت */
    BACK,

    /** جزئیات */
    DETAIL,

    /** داخل کیف */
    INTERIOR,

    /** استایل / لایف‌استایل */
    STYLE,

    /** روی مدل */
    ON_MODEL,

    /** مقایسه‌ی اندازه */
    SCALE,
    ;

    companion object {
        /** The view as the contract sends it (`FRONT`), exactly; anything else is MEDIA_VIEW_INVALID. */
        fun makeNew(raw: String): Result<MediaView, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: the caller names it (`variants.0.media.1.view`).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("MEDIA_VIEW_INVALID")
    }
}
