package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions

/**
 * What a bag is like (the contract's ProductSpecs, every part of it): the product page's spec tab, comparisons, the
 * occasion filter and SEO. Everything is optional: a spec nobody filled in is simply not shown.
 *
 * Its parts check themselves (Dimensions, StrapInfo, PocketInfo, LiningInfo, HardwareInfo, WarrantyInfo,
 * CareInstructions); here are the limits on the whole: the weight, at most [MAX_STRAPS] straps, the laptop size and the
 * capacity, the stitching and where it was made. Every problem comes back at once, each under its own field
 * (`weightGrams`, `straps`), and the caller puts `specs` in front.
 */
@ConsistentCopyVisibility
data class ProductSpecs private constructor(
    val dimensions: Dimensions?,
    val weightGrams: Int?,
    val closure: ClosureType?,
    val straps: List<StrapInfo>,
    val pockets: PocketInfo?,
    val laptopMaxInch: Double?,
    val capacityLiters: Double?,
    val waterproof: Boolean,
    val occasions: Set<Occasion>,
    val fits: Set<FitItem>,
    val lining: LiningInfo?,
    val hardware: HardwareInfo?,
    val stitching: String?,
    val handmade: Boolean,
    val madeIn: String?,
    val careInstructions: CareInstructions,
    val warranty: WarrantyInfo?,
) {
    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly.
    init {
        require(weightGrams == null || weightGrams in 1..MAX_WEIGHT_GRAMS) { "Invalid weight $weightGrams" }
        require(straps.size <= MAX_STRAPS) { "Too many straps: ${straps.size}" }
        require(isPositiveUpTo(laptopMaxInch, MAX_LAPTOP_INCH) && isPositiveUpTo(capacityLiters, MAX_CAPACITY_LITERS)) { "Invalid laptop or capacity" }
        require(isText(stitching, MAX_STITCHING) && isText(madeIn, MAX_MADE_IN)) { "Invalid spec text" }
    }

    companion object {
        /** No bag weighs 20 kg; a bigger number is a typo. */
        const val MAX_WEIGHT_GRAMS = 20_000
        const val MAX_STRAPS = 4
        const val MAX_LAPTOP_INCH = 20.0
        const val MAX_CAPACITY_LITERS = 100.0
        const val MAX_STITCHING = 100
        const val MAX_MADE_IN = 40

        val NONE = ProductSpecs(
            null, null, null, emptyList(), null, null, null, false, emptySet(), emptySet(), null, null, null, false, null, CareInstructions.NONE, null,
        )

        /** The parts already made by their own value objects; every limit on the whole checked here, all at once. */
        fun makeNew(
            dimensions: Dimensions? = null,
            weightGrams: Int? = null,
            closure: ClosureType? = null,
            straps: List<StrapInfo> = emptyList(),
            pockets: PocketInfo? = null,
            laptopMaxInch: Double? = null,
            capacityLiters: Double? = null,
            waterproof: Boolean = false,
            occasions: Set<Occasion> = emptySet(),
            fits: Set<FitItem> = emptySet(),
            lining: LiningInfo? = null,
            hardware: HardwareInfo? = null,
            stitching: String? = null,
            handmade: Boolean = false,
            madeIn: String? = null,
            careInstructions: CareInstructions = CareInstructions.NONE,
            warranty: WarrantyInfo? = null,
        ): Result<ProductSpecs, InputProblems> {
            val stitchingText = clean(stitching)
            val madeInText = clean(madeIn)
            val problems = problemsOfWhole(weightGrams, straps.size, laptopMaxInch, capacityLiters, stitching, madeIn)
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(
                ProductSpecs(
                    dimensions, weightGrams, closure, straps.toList(), pockets, laptopMaxInch, capacityLiters, waterproof, occasions.toSet(),
                    fits.toSet(), lining, hardware, stitchingText, handmade, madeInText, careInstructions, warranty,
                ),
            )
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            dimensions: Dimensions? = null,
            weightGrams: Int? = null,
            closure: ClosureType? = null,
            straps: List<StrapInfo> = emptyList(),
            pockets: PocketInfo? = null,
            laptopMaxInch: Double? = null,
            capacityLiters: Double? = null,
            waterproof: Boolean = false,
            occasions: Set<Occasion> = emptySet(),
            fits: Set<FitItem> = emptySet(),
            lining: LiningInfo? = null,
            hardware: HardwareInfo? = null,
            stitching: String? = null,
            handmade: Boolean = false,
            madeIn: String? = null,
            careInstructions: CareInstructions = CareInstructions.NONE,
            warranty: WarrantyInfo? = null,
        ): ProductSpecs = ProductSpecs(
            dimensions, weightGrams, closure, straps.toList(), pockets, laptopMaxInch, capacityLiters, waterproof, occasions.toSet(), fits.toSet(),
            lining, hardware, stitching, handmade, madeIn, careInstructions, warranty,
        )

        /**
         * The limits on the whole alone, for a form whose parts are not all valid yet: a caller that checks the parts
         * on their own still reports these together with theirs.
         */
        fun problemsOfWhole(
            weightGrams: Int?,
            strapsCount: Int,
            laptopMaxInch: Double?,
            capacityLiters: Double?,
            stitching: String?,
            madeIn: String?,
        ): List<Problem> = buildList {
            if (weightGrams != null && weightGrams !in 1..MAX_WEIGHT_GRAMS) add(Problem.WeightInvalid)
            if (strapsCount > MAX_STRAPS) add(Problem.StrapsTooMany)
            if (!isPositiveUpTo(laptopMaxInch, MAX_LAPTOP_INCH)) add(Problem.LaptopInvalid)
            if (!isPositiveUpTo(capacityLiters, MAX_CAPACITY_LITERS)) add(Problem.CapacityInvalid)
            if ((clean(stitching)?.length ?: 0) > MAX_STITCHING) add(Problem.StitchingTooLong)
            if ((clean(madeIn)?.length ?: 0) > MAX_MADE_IN) add(Problem.MadeInTooLong)
        }

        private fun clean(text: String?): String? = text?.normalizePersianLetters()?.ifEmpty { null }

        private fun isPositiveUpTo(value: Double?, max: Double): Boolean = value == null || (value.isFinite() && value > 0 && value <= max)

        private fun isText(text: String?, max: Int): Boolean = text == null || (text.isNotBlank() && text.length <= max)
    }

    sealed class Problem(code: String, field: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object WeightInvalid : Problem("WEIGHT_INVALID", "weightGrams", mapOf("maxGrams" to MAX_WEIGHT_GRAMS))
        data object StrapsTooMany : Problem("STRAPS_TOO_MANY", "straps", mapOf("maxItems" to MAX_STRAPS))
        data object LaptopInvalid : Problem("LAPTOP_MAX_INCH_INVALID", "laptopMaxInch", mapOf("max" to MAX_LAPTOP_INCH.toInt()))
        data object CapacityInvalid : Problem("CAPACITY_LITERS_INVALID", "capacityLiters", mapOf("max" to MAX_CAPACITY_LITERS.toInt()))
        data object StitchingTooLong : Problem("STITCHING_TOO_LONG", "stitching", mapOf("maxLength" to MAX_STITCHING))
        data object MadeInTooLong : Problem("MADE_IN_TOO_LONG", "madeIn", mapOf("maxLength" to MAX_MADE_IN))
    }
}
