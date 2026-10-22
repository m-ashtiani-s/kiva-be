package com.kiva.domain.aggregate.review.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/** A review's id (`reviewId`): a positive number. Anything else, in a path, is the same 404 as one nobody has. */
@JvmInline
value class ReviewId private constructor(val value: Long) {
    init {
        require(value > 0) { "Invalid ReviewId $value" }
    }

    companion object {
        fun makeNew(raw: String?): Result<ReviewId, Failure> =
            raw?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { Result.success(ReviewId(it)) } ?: Result.failure(Failure.NotFound)

        fun constructFromPersisted(value: Long): ReviewId = ReviewId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.NOT_FOUND) {
        data object NotFound : Failure("REVIEW_NOT_FOUND")
    }
}

/**
 * Where a review is (the contract's `ReviewStatus`; roadmap section 13.2, decision 10): waiting for support (PENDING),
 * on the site ([APPROVED]), or not ([REJECTED]). Only an approved one counts in the product's rating.
 */
enum class ReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    ;

    /** PENDING or APPROVED: at most one of these per user and product (decision 2). */
    val isLive: Boolean get() = this != REJECTED

    companion object {
        /** The panel's `status` filter, exactly as the contract names it; anything else is REVIEW_STATUS_INVALID. */
        fun makeNew(raw: String): Result<ReviewStatus, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "status") {
        data object Invalid : Failure("REVIEW_STATUS_INVALID")
    }
}

/** One to five stars. */
@JvmInline
value class StarRating private constructor(val value: Int) {
    init {
        require(value in MIN..MAX) { "Invalid StarRating $value" }
    }

    companion object {
        const val MIN = 1
        const val MAX = 5

        fun makeNew(raw: Int): Result<StarRating, Failure> =
            if (raw in MIN..MAX) Result.success(StarRating(raw)) else Result.failure(Failure.Invalid)

        fun constructFromPersisted(value: Int): StarRating = StarRating(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "rating") {
        data object Invalid : Failure("RATING_INVALID")
    }
}

/**
 * The name a reviewer chose to show (decision 5): at most [MAX_LENGTH], letters normalized; blank is none, shown as
 * «کاربر کیوا» (ReviewTexts). The account's own name is never put in by itself. No field here: the caller names it.
 */
@JvmInline
value class ReviewAuthorName private constructor(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none(Char::isISOControl)) { "Invalid ReviewAuthorName of length ${value.length}" }
    }

    override fun toString(): String = "ReviewAuthorName(***)"

    companion object {
        const val MAX_LENGTH = 40

        fun makeNew(raw: String?): Result<ReviewAuthorName?, ResultFailure> {
            val cleaned = raw?.normalizePersianLetters().orEmpty()
            return when {
                cleaned.isEmpty() -> Result.success(null)
                cleaned.length > MAX_LENGTH -> Result.failure(TextTooLong(MAX_LENGTH))
                cleaned.any(Char::isISOControl) -> Result.failure(TextInvalid)
                else -> Result.success(ReviewAuthorName(cleaned))
            }
        }

        fun constructFromPersisted(value: String): ReviewAuthorName = ReviewAuthorName(value)
    }
}

/**
 * How support may reach the reviewer (decision 6): an Iranian mobile (`09…`, digits normalized) or an email (lower
 * case). Only the panel sees it. Personal: never logged.
 */
@JvmInline
value class ReviewContact private constructor(val value: String) {
    init {
        require(value.length <= MAX_LENGTH && (MOBILE.matches(value) || EMAIL.matches(value))) { "Invalid ReviewContact of length ${value.length}" }
    }

    override fun toString(): String = "ReviewContact(***)"

    companion object {
        const val MAX_LENGTH = 100
        private val MOBILE = Regex("^09[0-9]{9}$")
        private val EMAIL = Regex("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")

        /** Missing or blank is none. */
        fun makeNew(raw: String?): Result<ReviewContact?, Failure> {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Result.success(null)
            IranianMobileNumber.makeNew(text).getOrNull()?.let { return Result.success(ReviewContact(it.value)) }
            val email = text.lowercase()
            return if (email.length <= MAX_LENGTH && EMAIL.matches(email)) Result.success(ReviewContact(email)) else Result.failure(Failure.Invalid)
        }

        fun constructFromPersisted(value: String): ReviewContact = ReviewContact(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "contact") {
        data object Invalid : Failure("CONTACT_INVALID")
    }
}

/**
 * A plain text of a review (decision 4): the review's own, Kiva's reply, a reason to turn it down. Not HTML: shown as
 * text, so `<` is fine. Trimmed, letters normalized line by line, line breaks kept but more than one empty line in a row
 * becomes one; a control character other than a line break is refused (TEXT_INVALID). Each use has its own shortest and
 * longest length (TEXT_TOO_SHORT with `meta.minLength`, TEXT_TOO_LONG with `meta.maxLength`); missing or blank is
 * [missing]. No field here: the caller names it. Personal: never logged.
 */
@JvmInline
value class ReviewText private constructor(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH && value.none { it.isISOControl() && it != '\n' }) { "Invalid ReviewText of length ${value.length}" }
    }

    override fun toString(): String = "ReviewText(***)"

    companion object {
        const val MAX_LENGTH = 1000

        /** A review's text: 10 to 1000. */
        const val MIN_REVIEW = 10

        /** Kiva's reply: 2 to 1000. */
        const val MIN_REPLY = 2

        /** A reason to turn a review down: 3 to 300 (decision 10). */
        const val MIN_REASON = 3
        const val MAX_REASON = 300

        /** [tooShort] is what a text shorter than [minLength] is: TEXT_TOO_SHORT unless the use has its own (a reason). */
        fun makeNew(
            raw: String?,
            minLength: Int,
            maxLength: Int,
            missing: ResultFailure,
            tooShort: ResultFailure = TextTooShort(minLength),
        ): Result<ReviewText, ResultFailure> {
            require(minLength in 1..maxLength && maxLength <= MAX_LENGTH) { "A review's text of $minLength to $maxLength" }
            val cleaned = clean(raw.orEmpty())
            return when {
                cleaned.isEmpty() -> Result.failure(missing)
                cleaned.any { it.isISOControl() && it != '\n' } -> Result.failure(TextInvalid)
                cleaned.length > maxLength -> Result.failure(TextTooLong(maxLength))
                cleaned.length < minLength -> Result.failure(tooShort)
                else -> Result.success(ReviewText(cleaned))
            }
        }

        fun constructFromPersisted(value: String): ReviewText = ReviewText(value)

        private fun clean(raw: String): String =
            raw.replace("\r\n", "\n").replace('\r', '\n')
                .split('\n').joinToString("\n") { it.normalizePersianLetters() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
    }
}

/** A text shorter than [minLength] (roadmap phase 8, decision 29); the field is added where it is found. */
data class TextTooShort(val minLength: Int) : ResultFailure("TEXT_TOO_SHORT", FailureKind.INVALID, meta = mapOf("minLength" to minLength))

/**
 * The delivered line of the reviewer's own order a review is linked to (decision 3): the badge «خریدار», and the color
 * they bought ([colorKey], [colorName], [colorHex] as the order's line has them).
 */
data class ReviewPurchase(
    val orderId: OrderId,
    val orderCode: OrderCode,
    val lineId: Int,
    val colorKey: ColorKey,
    val colorName: String,
    val colorHex: String,
) {
    init {
        require(lineId >= 1) { "A purchase of line $lineId" }
    }
}

/** Kiva's reply under a review (decision 12): its [text], when it was last saved and by whom. Its author is always «کیوا». */
data class ReviewReply(val text: ReviewText, val repliedOn: Instant, val repliedBy: UserId)
