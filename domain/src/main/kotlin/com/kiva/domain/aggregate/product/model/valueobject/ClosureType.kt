package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** How a bag closes. Values from the API contract; the Persian label is the HTTP layer's job. */
enum class ClosureType {
    /** زیپ فلزی */
    ZIPPER,

    /** زیپ دوطرفه */
    DOUBLE_ZIPPER,

    /** زیپ + قفل تزئینی */
    ZIPPER_WITH_DECORATIVE_LOCK,

    /** درب مگنتی */
    MAGNETIC_FLAP,

    /** دکمه مگنتی */
    MAGNETIC_SNAP,

    /** بند کشی */
    DRAWSTRING,

    /** دکمه فلزی */
    METAL_BUTTON,

    /** بدون درب */
    OPEN_TOP,
    ;

    companion object {
        /** The closure as the contract sends it (`ZIPPER`), exactly; anything else is CLOSURE_TYPE_INVALID. */
        fun makeNew(raw: String): Result<ClosureType, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // Always the `closure` field of the contract's ProductSpecs.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "closure") {
        data object Invalid : Failure("CLOSURE_TYPE_INVALID")
    }
}
