package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/** The kind of a strap or handle. Values from the API contract. */
enum class StrapType {
    SHOULDER,
    CROSSBODY,
    TOP_HANDLE,
    CHAIN,
    BACKPACK_STRAPS,
    WRISTLET,
    NONE,
    ;

    companion object {
        fun makeNew(raw: String): Result<StrapType, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "type") {
        data object Invalid : Failure("STRAP_TYPE_INVALID")
    }
}

/**
 * One strap or handle of a bag (the contract's StrapInfo). Lengths in centimeters, each above 0 and at most
 * [MAX_LENGTH_CM]; a range's minimum is not above its maximum. A strap stored before the full specs existed was one line
 * of text, and reads back as a strap with only its [label].
 */
@ConsistentCopyVisibility
data class StrapInfo private constructor(
    val type: StrapType?,
    val label: String?,
    val lengthCm: Double?,
    val minLengthCm: Double?,
    val maxLengthCm: Double?,
    val adjustable: Boolean,
    val detachable: Boolean,
    val material: String?,
) {
    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(isText(label, MAX_LABEL) && isText(material, MAX_MATERIAL)) { "Invalid StrapInfo texts" }
        require(listOf(lengthCm, minLengthCm, maxLengthCm).all(::isLength)) { "Invalid StrapInfo lengths" }
        require(minLengthCm == null || maxLengthCm == null || minLengthCm <= maxLengthCm) { "Invalid StrapInfo range" }
    }

    companion object {
        const val MAX_LABEL = 200
        const val MAX_MATERIAL = 60
        const val MAX_LENGTH_CM = 200.0

        /** Every problem at once, each under its own field (`lengthCm`, `material`); the caller puts the strap's place in front. */
        fun makeNew(
            type: String?,
            label: String?,
            lengthCm: Double?,
            minLengthCm: Double?,
            maxLengthCm: Double?,
            adjustable: Boolean?,
            detachable: Boolean?,
            material: String?,
        ): Result<StrapInfo, InputProblems> {
            val labelText = label?.normalizePersianLetters()?.ifEmpty { null }
            val materialText = material?.normalizePersianLetters()?.ifEmpty { null }
            val strapType = type?.trim()?.ifEmpty { null }?.let { StrapType.makeNew(it) }
            val problems = buildList {
                strapType?.failureOrNull()?.let(::add)
                if ((labelText?.length ?: 0) > MAX_LABEL) add(Problem.LabelTooLong)
                if (!isLength(lengthCm)) add(Problem.LengthInvalid("lengthCm"))
                if (!isLength(minLengthCm)) add(Problem.LengthInvalid("minLengthCm"))
                if (!isLength(maxLengthCm)) add(Problem.LengthInvalid("maxLengthCm"))
                if (isLength(minLengthCm) && isLength(maxLengthCm) && minLengthCm != null && maxLengthCm != null && minLengthCm > maxLengthCm) {
                    add(Problem.RangeInvalid)
                }
                if ((materialText?.length ?: 0) > MAX_MATERIAL) add(Problem.MaterialTooLong)
            }
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(
                StrapInfo(strapType?.getOrNull(), labelText, lengthCm, minLengthCm, maxLengthCm, adjustable ?: false, detachable ?: false, materialText),
            )
        }

        /** A strap stored as one line of text, before the full specs: only its label. */
        fun legacy(label: String): StrapInfo = StrapInfo(null, label, null, null, null, adjustable = false, detachable = false, material = null)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            type: StrapType?,
            label: String?,
            lengthCm: Double?,
            minLengthCm: Double?,
            maxLengthCm: Double?,
            adjustable: Boolean,
            detachable: Boolean,
            material: String?,
        ): StrapInfo = StrapInfo(type, label, lengthCm, minLengthCm, maxLengthCm, adjustable, detachable, material)

        private fun isLength(cm: Double?): Boolean = cm == null || (cm.isFinite() && cm > 0 && cm <= MAX_LENGTH_CM)

        private fun isText(text: String?, max: Int): Boolean = text == null || (text.isNotBlank() && text.length <= max)
    }

    sealed class Problem(code: String, field: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object LabelTooLong : Problem("STRAP_TOO_LONG", "label", mapOf("maxLength" to MAX_LABEL))
        data class LengthInvalid(val at: String) : Problem("STRAP_LENGTH_INVALID", at, mapOf("maxCm" to MAX_LENGTH_CM.toInt()))
        data object RangeInvalid : Problem("STRAP_LENGTH_RANGE_INVALID", "maxLengthCm")
        data object MaterialTooLong : Problem("STRAP_MATERIAL_TOO_LONG", "material", mapOf("maxLength" to MAX_MATERIAL))
    }
}
