package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters

/**
 * A bag's pockets (the contract's PocketInfo): each count 0 to [MAX_COUNT], and a [summary] line. Pockets stored before
 * the full specs existed were one line of text, and read back as only that summary.
 */
@ConsistentCopyVisibility
data class PocketInfo private constructor(
    val internal: Int,
    val internalZip: Int,
    val external: Int,
    val phone: Int,
    val cardSlots: Int,
    val billSlots: Int,
    val coinPocket: Boolean,
    val detachablePouch: Boolean,
    val laptopSleeve: Boolean,
    val summary: String?,
) {
    // Second wall: makeNew already refuses bad input with a Failure, so this only fails on broken stored data. A bug: stop loudly.
    init {
        require(listOf(internal, internalZip, external, phone, cardSlots, billSlots).all { it in 0..MAX_COUNT }) { "Invalid PocketInfo counts" }
        require(summary == null || (summary.isNotBlank() && summary.length <= MAX_SUMMARY)) { "Invalid PocketInfo summary" }
    }

    companion object {
        const val MAX_COUNT = 20
        const val MAX_SUMMARY = 200

        /** Missing counts are 0 and missing flags false. Every problem at once, each under its own field. */
        fun makeNew(
            internal: Int?,
            internalZip: Int?,
            external: Int?,
            phone: Int?,
            cardSlots: Int?,
            billSlots: Int?,
            coinPocket: Boolean?,
            detachablePouch: Boolean?,
            laptopSleeve: Boolean?,
            summary: String?,
        ): Result<PocketInfo, InputProblems> {
            val counts = listOf("internal" to internal, "internalZip" to internalZip, "external" to external, "phone" to phone, "cardSlots" to cardSlots, "billSlots" to billSlots)
            val summaryText = summary?.normalizePersianLetters()?.ifEmpty { null }
            val problems = buildList {
                counts.filter { (_, count) -> count != null && count !in 0..MAX_COUNT }.forEach { (field, _) -> add(Problem.CountInvalid(field)) }
                if ((summaryText?.length ?: 0) > MAX_SUMMARY) add(Problem.SummaryTooLong)
            }
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(
                PocketInfo(
                    internal ?: 0, internalZip ?: 0, external ?: 0, phone ?: 0, cardSlots ?: 0, billSlots ?: 0,
                    coinPocket ?: false, detachablePouch ?: false, laptopSleeve ?: false, summaryText,
                ),
            )
        }

        /** Pockets stored as one line of text, before the full specs: only the summary. */
        fun legacy(summary: String): PocketInfo = PocketInfo(0, 0, 0, 0, 0, 0, coinPocket = false, detachablePouch = false, laptopSleeve = false, summary = summary)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            internal: Int,
            internalZip: Int,
            external: Int,
            phone: Int,
            cardSlots: Int,
            billSlots: Int,
            coinPocket: Boolean,
            detachablePouch: Boolean,
            laptopSleeve: Boolean,
            summary: String?,
        ): PocketInfo = PocketInfo(internal, internalZip, external, phone, cardSlots, billSlots, coinPocket, detachablePouch, laptopSleeve, summary)
    }

    sealed class Problem(code: String, field: String, meta: Map<String, Any>) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data class CountInvalid(val at: String) : Problem("POCKET_COUNT_INVALID", at, mapOf("max" to MAX_COUNT))
        data object SummaryTooLong : Problem("POCKETS_TOO_LONG", "summary", mapOf("maxLength" to MAX_SUMMARY))
    }
}
