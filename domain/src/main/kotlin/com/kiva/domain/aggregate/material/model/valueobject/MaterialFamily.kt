package com.kiva.domain.aggregate.material.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** The broad kind of a material, for grouping and filters. Values from the API contract. */
enum class MaterialFamily {
    GENUINE_LEATHER,
    FAUX_LEATHER,
    CANVAS,
    SUEDE,
    FAUX_SUEDE,
    SATIN,
    VELVET,
    COTTON,
    OTHER,
    ;

    companion object {
        /** The family as the contract sends it (`CANVAS`), exactly; anything else is MATERIAL_FAMILY_INVALID. */
        fun makeNew(raw: String): Result<MaterialFamily, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // Always the `family` field of the contract's MaterialUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "family") {
        data object Invalid : Failure("MATERIAL_FAMILY_INVALID")
    }
}
