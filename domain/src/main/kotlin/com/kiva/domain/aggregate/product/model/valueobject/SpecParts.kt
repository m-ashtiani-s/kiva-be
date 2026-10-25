package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

// The small parts of a product's specs (the contract's ProductSpecs): what fits inside, the lining, the hardware and
// the warranty. Texts are trimmed, Persian letters normalized, blank is none.

/** What fits inside a bag («چی توش جا می‌شه؟»). Values from the API contract. */
enum class FitItem {
    PHONE,
    CARDS,
    KEYS,
    SUNGLASSES,
    MAKEUP_POUCH,
    WATER_BOTTLE,
    UMBRELLA,
    A5_NOTEBOOK,
    A4_DOCUMENTS,
    TABLET_11,
    LAPTOP_14,
    ;

    companion object {
        fun makeNew(raw: String): Result<FitItem, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field: one item of the `fits` list; the caller names which.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("FIT_ITEM_INVALID")
    }
}

/** The lining: its material (at most 60) and color (at most 40). Both blank is no lining info at all. */
@ConsistentCopyVisibility
data class LiningInfo private constructor(val material: String?, val color: String?) {
    init {
        require(fits(material, MAX_MATERIAL) && fits(color, MAX_COLOR) && (material != null || color != null)) { "Invalid LiningInfo" }
    }

    companion object {
        const val MAX_MATERIAL = 60
        const val MAX_COLOR = 40

        fun makeNew(material: String?, color: String?): Result<LiningInfo?, InputProblems> {
            val m = clean(material)
            val c = clean(color)
            val problems = buildList {
                if ((m?.length ?: 0) > MAX_MATERIAL) add(SpecProblem("LINING_MATERIAL_TOO_LONG", "material", MAX_MATERIAL))
                if ((c?.length ?: 0) > MAX_COLOR) add(SpecProblem("LINING_COLOR_TOO_LONG", "color", MAX_COLOR))
            }
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(if (m == null && c == null) null else LiningInfo(m, c))
        }

        fun constructFromPersisted(material: String?, color: String?): LiningInfo = LiningInfo(material, color)
    }
}

/** The metal parts: their material (at most 60) and finish (at most 40). Both blank is no hardware info at all. */
@ConsistentCopyVisibility
data class HardwareInfo private constructor(val material: String?, val finish: String?) {
    init {
        require(fits(material, MAX_MATERIAL) && fits(finish, MAX_FINISH) && (material != null || finish != null)) { "Invalid HardwareInfo" }
    }

    companion object {
        const val MAX_MATERIAL = 60
        const val MAX_FINISH = 40

        fun makeNew(material: String?, finish: String?): Result<HardwareInfo?, InputProblems> {
            val m = clean(material)
            val f = clean(finish)
            val problems = buildList {
                if ((m?.length ?: 0) > MAX_MATERIAL) add(SpecProblem("HARDWARE_MATERIAL_TOO_LONG", "material", MAX_MATERIAL))
                if ((f?.length ?: 0) > MAX_FINISH) add(SpecProblem("HARDWARE_FINISH_TOO_LONG", "finish", MAX_FINISH))
            }
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(if (m == null && f == null) null else HardwareInfo(m, f))
        }

        fun constructFromPersisted(material: String?, finish: String?): HardwareInfo = HardwareInfo(material, finish)
    }
}

/** The warranty on stitching and hardware: 1 to 60 months, and a line about it (at most 200). */
@ConsistentCopyVisibility
data class WarrantyInfo private constructor(val months: Int, val description: String?) {
    init {
        require(months in 1..MAX_MONTHS && fits(description, MAX_DESCRIPTION)) { "Invalid WarrantyInfo" }
    }

    companion object {
        const val MAX_MONTHS = 60
        const val MAX_DESCRIPTION = 200

        /** `months` is what makes a warranty: without it there is none, even with a description. */
        fun makeNew(months: Int?, description: String?): Result<WarrantyInfo?, InputProblems> {
            val d = clean(description)
            val problems = buildList {
                if (months != null && months !in 1..MAX_MONTHS) add(SpecProblem("WARRANTY_MONTHS_INVALID", "months", MAX_MONTHS, "max"))
                if (months == null && d != null) add(SpecProblem("WARRANTY_MONTHS_INVALID", "months", MAX_MONTHS, "max"))
                if ((d?.length ?: 0) > MAX_DESCRIPTION) add(SpecProblem("WARRANTY_DESCRIPTION_TOO_LONG", "description", MAX_DESCRIPTION))
            }
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(months?.let { WarrantyInfo(it, d) })
        }

        fun constructFromPersisted(months: Int, description: String?): WarrantyInfo = WarrantyInfo(months, description)
    }
}

/** A spec part's problem: its code, the field inside the part, and the limit the client shows. */
class SpecProblem(code: String, field: String, limit: Int, limitName: String = "maxLength") :
    ResultFailure(code, FailureKind.INVALID, field, meta = mapOf(limitName to limit))

private fun clean(text: String?): String? = text?.normalizePersianLetters()?.ifEmpty { null }

private fun fits(text: String?, max: Int): Boolean = text == null || (text.isNotBlank() && text.length <= max)
