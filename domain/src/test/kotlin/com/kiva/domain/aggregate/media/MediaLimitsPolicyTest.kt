package com.kiva.domain.aggregate.media

import com.kiva.common.railway.FailureKind
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.ImageFormat
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaType
import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.media.model.valueobject.VideoContainer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What each purpose takes, in which order the checks come, and the sizes a photo is stored in. */
class MediaLimitsPolicyTest {

    private val mb = 1024L * 1024L
    private val photo = MediaProbe.Image(ImageFormat.JPEG, 4000, 3000)
    private val h264 = MediaProbe.Video(VideoContainer.MP4, VideoCodec.H264, 1280, 720, 24)

    private fun check(purpose: MediaPurpose, probe: MediaProbe, bytes: Long = mb) = MediaLimitsPolicy.check(purpose, probe, bytes)

    @Test
    fun `a jpeg, png or webp photo is taken for every purpose`() {
        MediaPurpose.entries.forEach { purpose ->
            ImageFormat.entries.forEach { format -> assertEquals(MediaType.IMAGE, check(purpose, MediaProbe.Image(format, 800, 600)).getOrNull(), "$purpose $format") }
        }
    }

    @Test
    fun `an mp4 in H264 is a video for products, the blog, the photos before shipping and the customers`() {
        listOf(MediaPurpose.PRODUCT, MediaPurpose.BLOG, MediaPurpose.PRE_SHIPMENT, MediaPurpose.RETURN_EVIDENCE, MediaPurpose.REVIEW_PHOTO).forEach {
            assertEquals(MediaType.VIDEO, check(it, h264).getOrNull(), "$it")
        }
    }

    @Test
    fun `a mov is taken only for the photos before shipping`() {
        val mov = h264.copy(container = VideoContainer.MOV)

        assertEquals(MediaType.VIDEO, check(MediaPurpose.PRE_SHIPMENT, mov).getOrNull())
        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", check(MediaPurpose.PRODUCT, mov).failureOrNull()?.code)
    }

    @Test
    fun `a video for a category, a banner or a testimonial is 415 with the photo types allowed`() {
        listOf(MediaPurpose.CATEGORY, MediaPurpose.BANNER, MediaPurpose.TESTIMONIAL).forEach { purpose ->
            val failure = check(purpose, h264).failureOrNull()
            assertEquals(FailureKind.UNSUPPORTED_MEDIA_TYPE, failure?.kind)
            assertEquals(listOf("image/jpeg", "image/png", "image/webp"), failure?.meta?.get("allowed"))
        }
    }

    @Test
    fun `an unsupported file is 415 with what the purpose takes`() {
        val failure = check(MediaPurpose.PRE_SHIPMENT, MediaProbe.Unsupported).failureOrNull()

        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", failure?.code)
        assertEquals(listOf("image/jpeg", "image/png", "image/webp", "video/mp4", "video/quicktime"), failure?.meta?.get("allowed"))
    }

    @Test
    fun `an HEVC video is 415 with the hint H264 - any other codec too`() {
        listOf(VideoCodec.HEVC, VideoCodec.OTHER).forEach { codec ->
            val failure = check(MediaPurpose.PRODUCT, h264.copy(codec = codec)).failureOrNull()
            assertEquals(listOf("UPLOAD_TYPE_NOT_ALLOWED", "H264"), listOf(failure?.code, failure?.meta?.get("hint")), "$codec")
        }
    }

    @Test
    fun `a photo over 15 MB is 413 with the cap - 15 MB is fine - and a customer's over 8 MB`() {
        assertEquals(MediaType.IMAGE, check(MediaPurpose.PRODUCT, photo, 15 * mb).getOrNull())
        val staff = check(MediaPurpose.PRODUCT, photo, 15 * mb + 1).failureOrNull()
        assertEquals(listOf("UPLOAD_TOO_LARGE", 15 * mb), listOf(staff?.code, staff?.meta?.get("maxBytes")))
        assertEquals(FailureKind.PAYLOAD_TOO_LARGE, staff?.kind)
        assertEquals(8 * mb, check(MediaPurpose.REVIEW_PHOTO, photo, 8 * mb + 1).failureOrNull()?.meta?.get("maxBytes"))
    }

    @Test
    fun `a video over 200 MB, or a customer's over 50 MB, is 413`() {
        assertEquals(200 * mb, check(MediaPurpose.PRODUCT, h264, 200 * mb + 1).failureOrNull()?.meta?.get("maxBytes"))
        assertEquals(50 * mb, check(MediaPurpose.RETURN_EVIDENCE, h264, 50 * mb + 1).failureOrNull()?.meta?.get("maxBytes"))
    }

    @Test
    fun `a photo over 40 megapixels is IMAGE_TOO_LARGE, one whose short side is under 320 IMAGE_TOO_SMALL`() {
        assertEquals("IMAGE_TOO_LARGE", check(MediaPurpose.PRODUCT, MediaProbe.Image(ImageFormat.PNG, 8000, 6250)).failureOrNull()?.code)
        assertEquals(MediaType.IMAGE, check(MediaPurpose.PRODUCT, MediaProbe.Image(ImageFormat.PNG, 8000, 5000)).getOrNull())
        assertEquals("IMAGE_TOO_SMALL", check(MediaPurpose.PRODUCT, MediaProbe.Image(ImageFormat.PNG, 200, 200)).failureOrNull()?.code)
        assertEquals(MediaType.IMAGE, check(MediaPurpose.PRODUCT, MediaProbe.Image(ImageFormat.PNG, 320, 2000)).getOrNull())
        assertEquals(FailureKind.INVALID, check(MediaPurpose.PRODUCT, MediaProbe.Image(ImageFormat.PNG, 200, 200)).failureOrNull()?.kind)
    }

    @Test
    fun `a video over 180 seconds, or a customer's over 60, is VIDEO_TOO_LONG with the limit`() {
        assertEquals(180, check(MediaPurpose.PRODUCT, h264.copy(durationSec = 181)).failureOrNull()?.meta?.get("maxSeconds"))
        assertEquals(MediaType.VIDEO, check(MediaPurpose.PRODUCT, h264.copy(durationSec = 180)).getOrNull())
        assertEquals(60, check(MediaPurpose.REVIEW_PHOTO, h264.copy(durationSec = 61)).failureOrNull()?.meta?.get("maxSeconds"))
    }

    @Test
    fun `the type comes before the size - a huge SVG is 415, not 413`() {
        assertEquals("UPLOAD_TYPE_NOT_ALLOWED", check(MediaPurpose.PRODUCT, MediaProbe.Unsupported, 500 * mb).failureOrNull()?.code)
    }

    @Test
    fun `the cap while reading is the purpose's largest - 15 MB for a photo-only purpose, 200 MB with videos`() {
        assertEquals(15 * mb, MediaLimitsPolicy.limitsOf(MediaPurpose.CATEGORY).maxUploadBytes)
        assertEquals(200 * mb, MediaLimitsPolicy.limitsOf(MediaPurpose.PRODUCT).maxUploadBytes)
        assertEquals(200 * mb, MediaLimitsPolicy.MAX_UPLOAD_BYTES)
    }

    @Test
    fun `a photo is stored in every standard width below it and its own up to 1600 - never larger, always one`() {
        assertEquals(listOf(160, 480, 800, 1200, 1600), MediaLimitsPolicy.imageWidthsFor(4000))
        assertEquals(listOf(160, 480, 800, 1200, 1600), MediaLimitsPolicy.imageWidthsFor(1600))
        assertEquals(listOf(160, 400), MediaLimitsPolicy.imageWidthsFor(400))
        assertEquals(listOf(160, 320), MediaLimitsPolicy.imageWidthsFor(320))
        assertEquals(listOf(100), MediaLimitsPolicy.imageWidthsFor(100))
    }

    @Test
    fun `a customer's cap while reading is 50 MB, the largest of their purposes`() {
        assertEquals(50 * mb, MediaLimitsPolicy.MAX_CUSTOMER_UPLOAD_BYTES)
    }

    private val now = Instant.parse("2036-03-06T09:00:00Z")

    /** [count] uploads, one a minute, the last one [lastAgo] before now. */
    private fun uploads(count: Int, lastAgo: Duration = Duration.ofMinutes(1)): List<Instant> =
        (0 until count).map { now.minus(lastAgo).minus(Duration.ofMinutes(it.toLong())) }

    @Test
    fun `19 uploads in the last hour leave room for one more`() {
        assertNull(MediaLimitsPolicy.customerUploadRefusal(uploads(19), now))
        assertNull(MediaLimitsPolicy.customerUploadRefusal(emptyList(), now))
    }

    @Test
    fun `the 21st is 429 RATE_LIMITED until the oldest of the 20 is an hour old`() {
        // One a minute, the last 1 minute ago: the oldest was 20 minutes ago, so 40 minutes to wait.
        val refusal = MediaLimitsPolicy.customerUploadRefusal(uploads(20), now)

        assertEquals(Duration.ofMinutes(40), refusal?.retryAfter)
        assertEquals("RATE_LIMITED" to FailureKind.RATE_LIMITED, refusal?.code to refusal?.kind)
        assertEquals(2400L, refusal?.meta?.get("retryAfterSeconds"))
    }

    @Test
    fun `with more than 20 at once, the wait is until enough of them leave the hour - in any order, at least a second`() {
        // 22 uploads (two got in at once): the third oldest, 20 minutes ago, has to leave too.
        val refusal = MediaLimitsPolicy.customerUploadRefusal(uploads(22).shuffled(), now)
        assertEquals(Duration.ofMinutes(40), refusal?.retryAfter)

        val almost = MediaLimitsPolicy.customerUploadRefusal(List(20) { now.minus(Duration.ofMinutes(60)).plusMillis(10) }, now)
        assertEquals(1L, almost?.meta?.get("retryAfterSeconds"))
    }
}
