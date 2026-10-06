package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * What a bag is like: the product page's spec tab, comparisons and the occasion filter.
 *
 * Only what the shop has data for today (the prototype's spec per bag form). The rest of the contract's
 * ProductSpecs (lining, hardware, warranty, what fits inside, ...) comes with the admin panel. Everything is optional:
 * a spec nobody filled in is simply not shown.
 *
 * @property strap the strap in words («بند دوشی ثابت ۵۵ سانتی‌متر»).
 * @property pockets the pockets in words («۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل»).
 */
@ConsistentCopyVisibility
data class ProductSpecs private constructor(
    val dimensions: Dimensions?,
    val weightGrams: Int?,
    val closure: ClosureType?,
    val strap: String?,
    val pockets: String?,
    val occasions: Set<Occasion>,
) {
    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(weightGrams == null || weightGrams in 1..MAX_WEIGHT_GRAMS) { "Invalid weight $weightGrams" }
        require(isValidText(strap) && isValidText(pockets)) { "Invalid spec text: strap='$strap' pockets='$pockets'" }
    }

    companion object {
        /** No bag weighs 20 kg; a bigger number is a typo. */
        const val MAX_WEIGHT_GRAMS = 20_000
        const val MAX_TEXT_LENGTH = 200

        val NONE = ProductSpecs(null, null, null, null, null, emptySet())

        /**
         * Texts are trimmed and blank means "not given". Every problem is reported at once, each under its own field,
         * so the admin form can mark them all together.
         */
        fun makeNew(
            dimensions: Dimensions?,
            weightGrams: Int?,
            closure: ClosureType?,
            strap: String?,
            pockets: String?,
            occasions: Set<Occasion>,
        ): Result<ProductSpecs, Failure> {
            val strapText = strap?.trim()?.ifEmpty { null }
            val pocketsText = pockets?.trim()?.ifEmpty { null }

            val problems = buildList {
                if (weightGrams != null && weightGrams !in 1..MAX_WEIGHT_GRAMS) add(Failure.WeightInvalid)
                if (strapText != null && strapText.length > MAX_TEXT_LENGTH) add(Failure.StrapTooLong)
                if (pocketsText != null && pocketsText.length > MAX_TEXT_LENGTH) add(Failure.PocketsTooLong)
            }
            if (problems.isNotEmpty()) return Result.failure(Failure.Invalid(problems))

            return Result.success(ProductSpecs(dimensions, weightGrams, closure, strapText, pocketsText, occasions.toSet()))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            dimensions: Dimensions?,
            weightGrams: Int?,
            closure: ClosureType?,
            strap: String?,
            pockets: String?,
            occasions: Set<Occasion>,
        ): ProductSpecs = ProductSpecs(dimensions, weightGrams, closure, strap, pockets, occasions.toSet())

        private fun isValidText(text: String?): Boolean = text == null || (text.isNotBlank() && text.length <= MAX_TEXT_LENGTH)
    }

    // The whole failure is the `specs` field; each problem inside names its own field (specs.weightGrams, ...).
    sealed class Failure(
        code: String,
        field: String,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, FailureKind.INVALID, field = field, causes = causes, meta = meta) {
        data class Invalid(val problems: List<Failure>) : Failure("SPECS_INVALID", "specs", causes = problems)
        data object WeightInvalid : Failure("WEIGHT_INVALID", "weightGrams", meta = mapOf("maxGrams" to MAX_WEIGHT_GRAMS))
        data object StrapTooLong : Failure("STRAP_TOO_LONG", "strap", meta = mapOf("maxLength" to MAX_TEXT_LENGTH))
        data object PocketsTooLong : Failure("POCKETS_TOO_LONG", "pockets", meta = mapOf("maxLength" to MAX_TEXT_LENGTH))
    }
}
