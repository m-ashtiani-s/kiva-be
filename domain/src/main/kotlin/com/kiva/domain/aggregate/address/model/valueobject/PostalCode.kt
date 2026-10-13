package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits

/**
 * A 10-digit Iranian postal code, stored in one form: English digits, nothing else (`1965843117`). Typed as
 * «۱۹۶۵۸-۴۳۱۱۷» or with spaces, it is the same code.
 *
 * Only the length is checked, as the contract's pattern says: a stricter rule about which digits may appear could
 * refuse a real code, and a mistyped code still has the written address beside it.
 *
 * Personal data: [toString] never shows it.
 */
@JvmInline
value class PostalCode private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value.length == LENGTH && value.all { it in '0'..'9' }) { "Invalid PostalCode of length ${value.length}" }
    }

    override fun toString(): String = "PostalCode(***)"

    companion object {
        const val LENGTH = 10

        fun makeNew(raw: String): Result<PostalCode, Failure> {
            val cleaned = raw.normalizeDigits().filterNot { it == ' ' || it == '-' }
            return if (cleaned.length == LENGTH && cleaned.all { it in '0'..'9' }) {
                Result.success(PostalCode(cleaned))
            } else {
                Result.failure(Failure.Invalid)
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): PostalCode = PostalCode(value)
    }

    // Always the `postalCode` field of the contract's AddressInput.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "postalCode") {
        data object Invalid : Failure("POSTAL_CODE_INVALID")
    }
}
