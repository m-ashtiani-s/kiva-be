package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Where a parcel of a shipping method is followed: an https page with `{code}` standing for its tracking code, at most
 * 300 characters (shippingmethod_tbl's CHECK). `https://tipaxco.com/tracking?code={code}`.
 */
@JvmInline
value class TrackingUrlTemplate private constructor(val value: String) {

    init {
        require(isValid(value)) { "Invalid TrackingUrlTemplate '$value'" }
    }

    /** The page of the parcel [trackingCode]. */
    fun urlOf(trackingCode: String): String = value.replace(TRACKING_CODE, trackingCode)

    companion object {
        const val MAX_LENGTH = 300
        const val TRACKING_CODE = "{code}"

        private fun isValid(value: String): Boolean =
            value.length <= MAX_LENGTH && value.startsWith("https://") && TRACKING_CODE in value && value.none { it.isWhitespace() }

        fun makeNew(raw: String): Result<TrackingUrlTemplate, Failure> {
            val text = raw.trim()
            return if (isValid(text)) Result.success(TrackingUrlTemplate(text)) else Result.failure(Failure.Invalid)
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): TrackingUrlTemplate = TrackingUrlTemplate(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "trackingUrlTemplate") {
        data object Invalid : Failure("TRACKING_URL_TEMPLATE_INVALID")
    }
}
