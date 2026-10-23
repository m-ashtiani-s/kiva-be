package com.kiva.domain.aggregate.contact.model

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Problems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.TextInvalid
import com.kiva.common.railway.TextTooLong
import com.kiva.common.railway.forField
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.review.model.valueobject.TextTooShort
import com.kiva.domain.aggregate.user.model.valueobject.Email

/**
 * The «تماس با ما» form as the customer sent it (`POST /contact/messages`; roadmap phase 13, decision 2), checked on
 * its own shape: every problem at once, each under its field.
 *
 * - `fullName` 3 to 80, Persian letters made one form; no control character;
 * - `phone` a mobile in any form (Persian digits, +98), stored as `09…`;
 * - `email` optional, in lower case; empty is the same as not sent (a form's empty field);
 * - `orderCode` optional, `kv-218340` and Persian digits too, only a reference (never checked against the customer's orders);
 * - `message` plain text of 10 to 600: new lines kept, any other control character refused.
 *
 * Personal data: [toString] says none of it.
 */
@ConsistentCopyVisibility
data class ContactMessageDraft private constructor(
    val topic: ContactTopic,
    val fullName: String,
    val phone: IranianMobileNumber,
    val email: Email?,
    val orderCode: OrderCode?,
    val message: String,
) {
    override fun toString(): String = "ContactMessageDraft(topic=$topic, email=${email != null}, orderCode=${orderCode?.value})"

    /** The body as it came. */
    data class Input(
        val topic: String?,
        val fullName: String?,
        val phone: String?,
        val email: String?,
        val orderCode: String?,
        val message: String?,
    ) {
        override fun toString(): String = "Input(topic=$topic, orderCode=$orderCode)"
    }

    companion object {
        const val MIN_NAME = 3
        const val MAX_NAME = 80
        const val MIN_MESSAGE = 10
        const val MAX_MESSAGE = 600

        fun makeNew(input: Input): Result<ContactMessageDraft, InputProblems> {
            val problems = Problems()
            val topic = problems.take(topicOf(input.topic).forField("topic"))
            val name = problems.take(nameOf(input.fullName).forField("fullName"))
            val phone = problems.take(phoneOf(input.phone))
            val email = problems.take(emailOf(input.email))
            val orderCode = problems.take(orderCodeOf(input.orderCode))
            val message = problems.take(messageOf(input.message).forField("message"))
            if (problems.any()) return Result.failure(problems.all())
            return Result.success(ContactMessageDraft(topic.value, name.value, phone.value, email.value, orderCode.value, message.value))
        }

        private fun topicOf(raw: String?): Result<ContactTopic, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return ContactTopic.of(text)?.let { Result.success(it) } ?: Result.failure(TopicInvalid)
        }

        private fun nameOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.trim()?.normalizePersianLetters()?.ifEmpty { null } ?: return Result.failure(FieldRequired)
            return when {
                text.any { it.isISOControl() } -> Result.failure(TextInvalid)
                text.length < MIN_NAME -> Result.failure(TextTooShort(MIN_NAME))
                text.length > MAX_NAME -> Result.failure(TextTooLong(MAX_NAME))
                else -> Result.success(text)
            }
        }

        // IranianMobileNumber's failures name their field (`phone`) themselves.
        private fun phoneOf(raw: String?): Result<IranianMobileNumber, ResultFailure> =
            if (raw == null) Result.failure(IranianMobileNumber.Failure.Blank) else IranianMobileNumber.makeNew(raw)

        private fun emailOf(raw: String?): Result<Email?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return Email.makeNew(text)
        }

        private fun orderCodeOf(raw: String?): Result<OrderCode?, ResultFailure> {
            val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
            return OrderCode.makeNew(text)
        }

        /** Each line's letters made one form (a whole-text pass would fold the new lines into spaces), the ends trimmed. */
        private fun messageOf(raw: String?): Result<String, ResultFailure> {
            val text = raw?.replace("\r\n", "\n")?.lines()?.joinToString("\n") { it.normalizePersianLetters() }?.trim()?.ifEmpty { null }
                ?: return Result.failure(FieldRequired)
            return when {
                text.any { it.isISOControl() && it != '\n' } -> Result.failure(TextInvalid)
                text.length < MIN_MESSAGE -> Result.failure(TextTooShort(MIN_MESSAGE))
                text.length > MAX_MESSAGE -> Result.failure(TextTooLong(MAX_MESSAGE))
                else -> Result.success(text)
            }
        }
    }

    /** Not one of the five topics (decision 21). */
    data object TopicInvalid : ResultFailure("CONTACT_TOPIC_INVALID", FailureKind.INVALID)
}
