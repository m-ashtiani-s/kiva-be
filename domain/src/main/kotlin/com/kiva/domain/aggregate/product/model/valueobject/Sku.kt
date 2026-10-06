package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey

/**
 * The code staff and shoppers read on a product (`KV-1001`), and the code of each of its colors (`KV-1001-LILAC`).
 *
 * Uppercase Latin letters, digits and single dashes, starting with a letter. The capital first letter is what lets
 * `GET /products/{idOrSlug}` tell the three apart: digits only is an id, lowercase is a slug, uppercase is a code.
 *
 * When staff leave it empty, the code is made from the id: [forProduct] and [forVariant].
 */
@JvmInline
value class Sku private constructor(val value: String) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(value.length <= MAX_LENGTH && FORMAT.matches(value)) { "Invalid Sku '$value'" }
    }

    companion object {
        /** Longest code staff may type. A color's code adds `-` and its key, so it stays within [MAX_LENGTH]. */
        const val MAX_TYPED_LENGTH = 40
        const val MAX_LENGTH = 80

        private const val PREFIX = "KV"
        private const val NUMBER_OFFSET = 1000L

        // matches() checks the whole string, so no ^...$ is needed.
        private val FORMAT = Regex("[A-Z][A-Z0-9]*(-[A-Z0-9]+)*")

        /** A code typed by staff. Spaces around it are trimmed and letters made uppercase (`kv-1001` becomes `KV-1001`). */
        fun makeNew(raw: String): Result<Sku, Failure> {
            val normalized = raw.trim().uppercase()
            return if (normalized.length <= MAX_TYPED_LENGTH && FORMAT.matches(normalized)) {
                Result.success(Sku(normalized))
            } else {
                Result.failure(Failure.Invalid)
            }
        }

        /** The code a product gets when staff leave it empty: product 1 becomes `KV-1001`, as in the contract's example. */
        fun forProduct(id: ProductId): Sku = Sku("$PREFIX-${Math.addExact(id.value, NUMBER_OFFSET)}")

        /**
         * The code of one color of a product: the product's code plus the color key (`KV-1001` + lilac = `KV-1001-LILAC`).
         * Color keys are unique, so two colors of one product never share a code.
         */
        fun forVariant(productSku: Sku, colorKey: ColorKey): Sku = Sku("${productSku.value}-${colorKey.value.uppercase()}")

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): Sku = Sku(value)
    }

    // Always the `sku` field of the contract's product and variant requests.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "sku") {
        data object Invalid : Failure("SKU_INVALID")
    }
}
