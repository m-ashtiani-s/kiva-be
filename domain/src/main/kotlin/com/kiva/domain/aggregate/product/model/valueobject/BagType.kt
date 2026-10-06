package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * The form of a bag. It picks the illustration (category icons, product placeholders) and the default spec table.
 * Values and names from the API contract.
 */
enum class BagType {
    /** دوشی هوبو */
    HOBO,

    /** کراس‌بادی */
    CROSSBODY,

    /** کوله */
    BACKPACK,

    /** دستی */
    SATCHEL,

    /** کلاچ / مجلسی */
    CLUTCH,

    /** شاپر / توت */
    TOTE,

    /** سطلی */
    BUCKET,

    /** کیف پول */
    WALLET,
    ;

    companion object {
        /** The form as the contract sends it (`HOBO`), exactly; anything else is BAG_TYPE_INVALID. */
        fun makeNew(raw: String): Result<BagType, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // Always the `bagType` field of the contract's ProductUpsertRequest.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "bagType") {
        data object Invalid : Failure("BAG_TYPE_INVALID")
    }
}
