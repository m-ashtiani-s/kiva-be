package com.kiva.domain.aggregate.stockalert.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/** A subscription's id (`alertId`): a positive number. Anything else, in a path, is the same 404 as one nobody has. */
@JvmInline
value class StockAlertId private constructor(val value: Long) {
    init {
        require(value > 0) { "Invalid StockAlertId $value" }
    }

    companion object {
        fun makeNew(raw: String?): Result<StockAlertId, Failure> =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { Result.success(StockAlertId(it)) } ?: Result.failure(Failure.NotFound)

        fun constructFromPersisted(value: Long): StockAlertId = StockAlertId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.NOT_FOUND) {
        data object NotFound : Failure("STOCK_ALERT_NOT_FOUND")
    }
}

/** Waiting, told once, or called off (decision 6). */
enum class StockAlertStatus { ACTIVE, NOTIFIED, CANCELLED }
