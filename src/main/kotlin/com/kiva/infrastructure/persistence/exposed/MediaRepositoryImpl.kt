package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.media.repository.MediaRepository
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Reads and writes media_tbl. Runs inside the use case's transaction; it never opens one itself. */
class MediaRepositoryImpl : MediaRepository {

    override fun find(id: MediaId): MediaAsset? =
        MediaTbl.selectAll().where { MediaTbl.mediaId eq id.value }.singleOrNull()?.toAsset()

    override fun findAll(ids: Collection<MediaId>): List<MediaAsset> {
        if (ids.isEmpty()) return emptyList()
        return MediaTbl.selectAll().where { MediaTbl.mediaId inList ids.map { it.value }.distinct() }.map { it.toAsset() }
    }

    override fun insert(asset: MediaAsset) {
        MediaTbl.insert {
            it[mediaId] = asset.id.value
            it[purpose] = asset.purpose.name
            it[type] = asset.type.name
            it[mimeType] = asset.mimeType
            it[width] = asset.width
            it[height] = asset.height
            it[durationSec] = asset.durationSec
            it[bytes] = asset.bytes
            it[blurhash] = asset.blurhash
            it[variants] = JsonArray(asset.variants.map { variant -> JsonObject(mapOf("width" to JsonPrimitive(variant.width), "path" to JsonPrimitive(variant.path))) })
            it[posterPath] = asset.posterPath
            it[alt] = asset.alt?.value
            it[storageDir] = asset.storageDir
            it[uploadedBy] = asset.uploadedBy.value
            it[uploadedOn] = asset.uploadedOn.atOffset(ZoneOffset.UTC)
            it[attachedOn] = asset.attachedOn?.atOffset(ZoneOffset.UTC)
        }
    }

    override fun markAttached(ids: Collection<MediaId>, now: Instant): Int {
        if (ids.isEmpty()) return 0
        val values = ids.map { it.value }.distinct()
        MediaTbl.update({ (MediaTbl.mediaId inList values) and MediaTbl.attachedOn.isNull() }) { it[attachedOn] = now.atOffset(ZoneOffset.UTC) }
        return MediaTbl.select(MediaTbl.id).where { MediaTbl.mediaId inList values }.count().toInt()
    }

    override fun findUnattachedBefore(before: Instant, limit: Int): List<MediaId> =
        MediaTbl.select(MediaTbl.mediaId)
            .where { MediaTbl.attachedOn.isNull() and (MediaTbl.uploadedOn less before.atOffset(ZoneOffset.UTC)) }
            .orderBy(MediaTbl.uploadedOn to SortOrder.ASC)
            .limit(limit)
            .map { MediaId.constructFromPersisted(it[MediaTbl.mediaId]) }

    override fun deleteIfUnattached(id: MediaId, before: Instant): Boolean =
        MediaTbl.deleteWhere {
            (MediaTbl.mediaId eq id.value) and MediaTbl.attachedOn.isNull() and (MediaTbl.uploadedOn less before.atOffset(ZoneOffset.UTC))
        } == 1

    private fun ResultRow.toAsset() = MediaAsset.constructFromPersisted(
        id = MediaId.constructFromPersisted(this[MediaTbl.mediaId]),
        purpose = MediaPurpose.valueOf(this[MediaTbl.purpose]),
        type = MediaType.valueOf(this[MediaTbl.type]),
        mimeType = this[MediaTbl.mimeType],
        width = this[MediaTbl.width],
        height = this[MediaTbl.height],
        durationSec = this[MediaTbl.durationSec],
        bytes = this[MediaTbl.bytes],
        blurhash = this[MediaTbl.blurhash],
        variants = this[MediaTbl.variants].map { item ->
            val variant = item.jsonObject
            MediaVariant.of(variant.getValue("width").jsonPrimitive.int, variant.getValue("path").jsonPrimitive.content)
        },
        posterPath = this[MediaTbl.posterPath],
        alt = this[MediaTbl.alt]?.let(MediaAlt::constructFromPersisted),
        storageDir = this[MediaTbl.storageDir],
        uploadedBy = UserId.constructFromPersisted(this[MediaTbl.uploadedBy]),
        uploadedOn = this[MediaTbl.uploadedOn].toInstant(),
        attachedOn = this[MediaTbl.attachedOn]?.toInstant(),
    )
}
