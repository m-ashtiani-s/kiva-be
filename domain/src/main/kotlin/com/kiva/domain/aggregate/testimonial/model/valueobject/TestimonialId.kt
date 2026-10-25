package com.kiva.domain.aggregate.testimonial.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The id of a testimonial (its row's), given out by the repository when the panel makes one. */
@JvmInline
value class TestimonialId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid TestimonialId $value" }
    }

    companion object {
        /** An id as a path sent it; the panel answers a malformed one as it answers one nobody has (404). */
        fun makeNew(raw: Long): Result<TestimonialId, Failure> = if (raw > 0) Result.success(TestimonialId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): TestimonialId = TestimonialId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("TESTIMONIAL_ID_INVALID")
    }
}
