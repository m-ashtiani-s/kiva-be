package com.kiva.application.usecase.media

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.railway.zipAll
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.repository.MediaRepository
import java.time.Instant

/**
 * The rules for attaching uploaded files to something (roadmap section 5.2), shared by every use case that does it: a
 * category's photo, a color's gallery, an order's photos, a banner, a blog post. Runs inside the caller's transaction.
 *
 * 1. the file exists, or `MEDIA_NOT_FOUND` on its field;
 * 2. it was uploaded for this place (a PRE_SHIPMENT photo never becomes a product photo), or `MEDIA_PURPOSE_MISMATCH`;
 * 3. its type is allowed here (no video for a category), or `MEDIA_TYPE_NOT_ALLOWED`;
 *
 * and once the caller's change is made, [attach] marks the files used in the same transaction, so the nightly clean-up
 * never removes them.
 */
class MediaAttachments(private val mediaRepository: MediaRepository) {

    /** One file the form names, and the field it came in (`imageMediaId`, `variants.1.media.0.mediaId`). */
    data class Wanted(val id: MediaId, val field: String)

    /**
     * The files of [wanted], by id, when every one of them may be attached to a place that takes [purpose] files of
     * [types]; otherwise every problem at once, each under its field.
     */
    fun check(wanted: List<Wanted>, purpose: MediaPurpose, types: Set<MediaType>): Result<Map<MediaId, MediaAsset>, InputProblems> {
        if (wanted.isEmpty()) return Result.success(emptyMap())
        val found = mediaRepository.findAll(wanted.map { it.id }).associateBy { it.id }
        val checked = wanted.map { (id, field) ->
            val asset = found[id]
            when {
                asset == null -> Result.failure(Problem.NotFound)
                asset.purpose != purpose -> Result.failure(Problem.PurposeMismatch)
                asset.type !in types -> Result.failure(Problem.TypeNotAllowed)
                else -> Result.success(asset)
            }.forField(field)
        }
        return Result.zipAll(checked, ::InputProblems).mapOnSuccess { assets -> assets.associateBy { it.id } }
    }

    /** Marks [ids] as used, in the caller's transaction. */
    fun attach(ids: Collection<MediaId>, now: Instant) {
        if (ids.isNotEmpty()) mediaRepository.markAttached(ids, now)
    }

    /** What can be wrong with a file the form names; the caller's field is added by [check]. */
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object NotFound : Problem("MEDIA_NOT_FOUND")
        data object PurposeMismatch : Problem("MEDIA_PURPOSE_MISMATCH")
        data object TypeNotAllowed : Problem("MEDIA_TYPE_NOT_ALLOWED")
    }
}
