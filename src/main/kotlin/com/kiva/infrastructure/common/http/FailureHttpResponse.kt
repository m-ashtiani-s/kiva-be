package com.kiva.infrastructure.common.http

import com.kiva.common.railway.ResultFailure
import kotlinx.serialization.Serializable

/**
 * The JSON body of every error response:
 * ```json
 * { "code": "INVALID_ADDRESS",
 *   "causes": [ { "code": "POSTAL_CODE_INVALID", "field": "postalCode" },
 *               { "code": "MOBILE_INVALID", "field": "mobile" } ] }
 * ```
 * `field` and `causes` are left out when empty. The client shows a message based on `code`.
 */
@Serializable
data class FailureHttpResponse(
    val code: String,
    val field: String? = null,
    val causes: List<FailureHttpResponse> = emptyList(),
) {
    companion object {
        fun from(failure: ResultFailure): FailureHttpResponse =
            FailureHttpResponse(
                code = failure.code,
                field = failure.field,
                causes = failure.causes.map(::from),
            )
    }
}
