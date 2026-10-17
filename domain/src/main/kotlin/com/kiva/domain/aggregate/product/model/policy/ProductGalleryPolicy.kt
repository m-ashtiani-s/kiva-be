package com.kiva.domain.aggregate.product.model.policy

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView

/**
 * The rules of one color's gallery as the panel's form sends it (contract ProductMediaInput): at most [MAX_ITEMS], each
 * file once, at most one marked primary; and its final order: the primary one first, then by `sortOrder` (one without
 * comes after those with one), then as listed. The first of the gallery is the card photo.
 */
object ProductGalleryPolicy {

    /** From the contract (`media.maxItems`). */
    const val MAX_ITEMS = 20

    /** One item of the form, already shaped: its file, its side, its alt, and how the panel asked to place it. */
    data class Entry(val mediaId: MediaId, val view: MediaView, val alt: MediaAlt?, val isPrimary: Boolean, val sortOrder: Int?)

    /**
     * The gallery in its final order, or every problem at once, each under its place in the list (`1.mediaId`,
     * `2.isPrimary`; the caller puts the list's own path in front), and MEDIA_TOO_MANY with no field of its own (the
     * list itself).
     */
    fun order(entries: List<Entry>): Result<List<Entry>, InputProblems> {
        val problems = buildList {
            if (entries.size > MAX_ITEMS) add(Problem.TooMany)
            val seen = mutableSetOf<MediaId>()
            entries.forEachIndexed { index, entry ->
                if (!seen.add(entry.mediaId)) add(Problem.Duplicate("$index.mediaId"))
            }
            entries.withIndex().filter { it.value.isPrimary }.drop(1).forEach { add(Problem.SecondPrimary("${it.index}.isPrimary")) }
        }
        if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))

        val ordered = entries.withIndex().sortedWith(
            compareBy<IndexedValue<Entry>>({ !it.value.isPrimary }, { it.value.sortOrder == null }, { it.value.sortOrder ?: 0 }, { it.index }),
        )
        return Result.success(ordered.map { it.value })
    }

    sealed class Problem(code: String, field: String?, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field, meta = meta) {
        data object TooMany : Problem("MEDIA_TOO_MANY", null, mapOf("maxItems" to MAX_ITEMS))
        data class Duplicate(val at: String) : Problem("MEDIA_DUPLICATE", at)
        data class SecondPrimary(val at: String) : Problem("MEDIA_PRIMARY_DUPLICATE", at)
    }
}
