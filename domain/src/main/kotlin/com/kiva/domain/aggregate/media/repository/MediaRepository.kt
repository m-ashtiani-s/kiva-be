package com.kiva.domain.aggregate.media.repository

import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import java.time.Instant

/** The uploaded files, kept as rows (media_tbl). The files themselves are on the disk, behind the MediaStorage port. */
interface MediaRepository {

    fun find(id: MediaId): MediaAsset?

    /** The ones of [ids] that exist, in no particular order. */
    fun findAll(ids: Collection<MediaId>): List<MediaAsset>

    fun insert(asset: MediaAsset)

    /**
     * Marks [ids] as used by something, the first time only (an attached file is never removed). Returns how many of
     * them exist: fewer than asked means one was removed meanwhile.
     */
    fun markAttached(ids: Collection<MediaId>, now: Instant): Int

    /** At most [limit] files attached to nothing and uploaded before [before], oldest first. */
    fun findUnattachedBefore(before: Instant, limit: Int): List<MediaId>

    /**
     * Removes [id]'s row, but only while it is still attached to nothing and was uploaded before [before]: one attached
     * in the meantime stays. Returns whether it was removed.
     */
    fun deleteIfUnattached(id: MediaId, before: Instant): Boolean
}

