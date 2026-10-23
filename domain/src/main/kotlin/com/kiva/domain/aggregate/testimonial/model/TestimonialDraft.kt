package com.kiva.domain.aggregate.testimonial.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldFailure
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersianLetters
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.review.model.valueobject.StarRating
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel
import java.time.Instant

/**
 * A testimonial as the panel's form sends it (`POST`/`PUT /admin/testimonials*`; roadmap section 16.2, decision 11),
 * checked on its own shape: every problem at once, each under its field. `PUT` replaces the whole testimonial. Whether
 * the product is there, the colour is one of it and the screenshot a TESTIMONIAL image is the use case's.
 *
 * @property messageTime the time shown in the bubble (`10:12`), as typed in Latin digits; a real clock time.
 */
@ConsistentCopyVisibility
data class TestimonialDraft private constructor(
    val customerName: String,
    val city: String?,
    val source: SocialChannel,
    val message: String,
    val messageTime: String?,
    val receivedAt: Instant?,
    val rating: StarRating,
    val productId: ProductId,
    val colorKey: ColorKey?,
    val screenshotMediaId: MediaId?,
    val isPublished: Boolean,
    val sortOrder: Int,
) {
    /** The form as it came; [receivedAt] already read (DATE_TIME_INVALID is the caller's). */
    data class Input(
        val customerName: String?,
        val city: String?,
        val source: String?,
        val message: String?,
        val messageTime: String?,
        val receivedAt: Result<Instant?, ResultFailure>,
        val rating: Int?,
        val productId: Long?,
        val colorKey: String?,
        val screenshotMediaId: String?,
        val isPublished: Boolean?,
        val sortOrder: Int?,
    )

    companion object {
        const val MAX_NAME = 40
        const val MAX_CITY = 40
        const val MAX_MESSAGE = 500
        private val CLOCK = Regex("([01][0-9]|2[0-3]):[0-5][0-9]")

        /** @param now the request's moment: a message can't have come after it. */
        fun makeNew(input: Input, now: Instant): Result<TestimonialDraft, InputProblems> {
            val problems = Problems()
            val name = problems.take(required(input.customerName, MAX_NAME).forField("customerName"))
            val city = problems.take(optional(input.city, MAX_CITY).forField("city"))
            val source = problems.take(sourceOf(input.source).forField("source"))
            val message = problems.take(messageOf(input.message).forField("message"))
            val time = problems.take(timeOf(input.messageTime).forField("messageTime"))
            val received = problems.take(input.receivedAt.forField("receivedAt"))
            input.receivedAt.getOrNull()?.let { if (it.isAfter(now)) problems.add(FieldFailure("receivedAt", Problem.InFuture)) }
            val rating = problems.take((input.rating?.let { StarRating.makeNew(it) } ?: Result.success(StarRating.constructFromPersisted(StarRating.MAX))).forField("rating"))
            val product = problems.take(productOf(input.productId).forField("productId"))
            val color = problems.take(colorOf(input.colorKey).forField("colorKey"))
            val screenshot = problems.take(screenshotOf(input.screenshotMediaId).forField("screenshotMediaId"))
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(
                TestimonialDraft(
                    name.value, city.value, source.value, message.value, time.value, received.value, rating.value, product.value, color.value,
                    screenshot.value, input.isPublished ?: true, input.sortOrder ?: 0,
                ),
            )
        }

        private fun required(raw: String?, max: Int): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        private fun optional(raw: String?, max: Int): Result<String?, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.success(null)
            return if (text.length > max) Result.failure(TextTooLong(max)) else Result.success(text)
        }

        /** Plain text: new lines kept, any other control character refused (TEXT_INVALID). */
        private fun messageOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return when {
                text.length > MAX_MESSAGE -> Result.failure(TextTooLong(MAX_MESSAGE))
                text.any { it.isISOControl() && it != '\n' } -> Result.failure(TextInvalid)
                else -> Result.success(text)
            }
        }

        private fun sourceOf(raw: String?): Result<SocialChannel, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return SocialChannel.entries.firstOrNull { it.name == text }?.let { Result.success(it) } ?: Result.failure(Problem.SourceInvalid)
        }

        private fun timeOf(raw: String?): Result<String?, ResultFailure> {
            val text = raw?.trim()?.normalizeDigits()?.ifEmpty { null } ?: return Result.success(null)
            return if (CLOCK.matches(text)) Result.success(text) else Result.failure(Problem.TimeInvalid)
        }

        private fun productOf(raw: Long?): Result<ProductId, ResultFailure> {
            if (raw == null) return Result.failure(FieldRequired)
            return ProductId.makeNew(raw).let { if (it is Result.Success) it else Result.failure(Problem.ProductNotFound) }
        }

        private fun colorOf(raw: String?): Result<ColorKey?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return ColorKey.makeNew(text)
        }

        private fun screenshotOf(raw: String?): Result<MediaId?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return MediaId.makeNew(text)
        }
    }

    // No field here: makeNew names each.
    sealed class Problem(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object SourceInvalid : Problem("SOCIAL_CHANNEL_INVALID")
        data object TimeInvalid : Problem("MESSAGE_TIME_INVALID")
        data object InFuture : Problem("DATE_IN_FUTURE")
        data object ProductNotFound : Problem("PRODUCT_NOT_FOUND")
    }
}
