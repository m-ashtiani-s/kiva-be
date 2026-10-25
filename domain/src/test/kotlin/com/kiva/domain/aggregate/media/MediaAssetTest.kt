package com.kiva.domain.aggregate.media

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** An uploaded file: what a photo and a video are made of, and what neither may be. */
class MediaAssetTest {

    @Test
    fun `a photo's main file is its largest size and its thumbnail its smallest - webp, attached to nothing`() {
        val photo = MediaFixtures.photo()

        assertEquals("${photo.storageDir}/w1600.webp", photo.mainPath)
        assertEquals("${photo.storageDir}/w160.webp", photo.thumbnailPath)
        assertEquals("image/webp", photo.mimeType)
        assertNull(photo.attachedOn)
        assertFalse(photo.isAttached)
    }

    @Test
    fun `a video's main file is its mp4 and its thumbnail the smaller cover`() {
        val video = MediaFixtures.video()

        assertEquals("${video.storageDir}/video.mp4", video.mainPath)
        assertEquals("${video.storageDir}/poster-w480.webp", video.thumbnailPath)
        assertEquals("video/mp4", video.mimeType)
    }

    @Test
    fun `the size to show at a width is the largest not wider - or the smallest when all are wider`() {
        val photo = MediaFixtures.photo(widths = listOf(160, 480, 640))

        assertEquals("${photo.storageDir}/w640.webp", photo.pathFor(800))
        assertEquals("${photo.storageDir}/w480.webp", photo.pathFor(480))
        assertEquals("${photo.storageDir}/w160.webp", photo.pathFor(100))
    }

    @Test
    fun `sizes given in any order are kept smallest first`() {
        val dir = "2036/03/${MediaFixtures.id().value}"
        val photo = MediaAsset.upload(
            MediaFixtures.id(), MediaPurpose.PRODUCT, MediaType.IMAGE, 800, 600, null, 1000, null,
            listOf(MediaVariant.of(800, "$dir/w800.webp"), MediaVariant.of(160, "$dir/w160.webp")), null, null, dir, UserId.SYSTEM, MediaFixtures.NOW,
        )

        assertEquals(listOf(160, 800), photo.variants.map { it.width })
    }

    @Test
    fun `a photo with a length, a video without its cover, or a size outside its folder is a bug`() {
        val dir = "2036/03/${MediaFixtures.id().value}"
        val sizes = listOf(MediaVariant.of(160, "$dir/w160.webp"))
        fun upload(type: MediaType, duration: Int?, poster: String?, variants: List<MediaVariant> = sizes) = MediaAsset.upload(
            MediaFixtures.id(), MediaPurpose.PRODUCT, type, 160, 120, duration, 1000, null, variants, poster, null, dir, UserId.SYSTEM, MediaFixtures.NOW,
        )

        assertFailsWith<IllegalArgumentException> { upload(MediaType.IMAGE, 12, null) }
        assertFailsWith<IllegalArgumentException> { upload(MediaType.VIDEO, 12, null) }
        assertFailsWith<IllegalArgumentException> { upload(MediaType.VIDEO, null, "$dir/poster-w480.webp") }
        assertFailsWith<IllegalArgumentException> { upload(MediaType.IMAGE, null, null, listOf(MediaVariant.of(160, "2036/03/other/w160.webp"))) }
        assertFailsWith<IllegalArgumentException> { upload(MediaType.IMAGE, null, null, emptyList()) }
    }
}
