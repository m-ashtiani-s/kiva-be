package com.kiva.domain.aggregate.ordernote.entity

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersianLetters
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * A note staff wrote on an order for each other (`AdminNote`): only the panel sees it, and it doesn't change the order.
 * Kept as a row, written once: never edited, never removed. Its identity is [id], given by the database when it is
 * stored (null before).
 */
class OrderNote private constructor(
    val id: Long?,
    val orderId: OrderId,
    val text: OrderNoteText,
    val authorId: UserId,
    val createdOn: Instant,
) {
    override fun equals(other: Any?): Boolean = other is OrderNote && id != null && other.id == id

    override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)

    override fun toString(): String = "OrderNote(id=$id, orderId=${orderId.value})"

    companion object {
        /** A new note by [by] on [orderId], written [now]. */
        fun write(orderId: OrderId, text: OrderNoteText, by: UserId, now: Instant): OrderNote = OrderNote(null, orderId, text, by, now)

        fun constructFromPersisted(id: Long, orderId: OrderId, text: OrderNoteText, authorId: UserId, createdOn: Instant): OrderNote =
            OrderNote(id, orderId, text, authorId, createdOn)
    }
}

/**
 * What the note says: 1 to [MAX_LENGTH] after trimming, letters normalized, line breaks kept as written. It may say
 * anything about the customer, so it is never logged: [toString] hides it.
 */
@JvmInline
value class OrderNoteText private constructor(val value: String) {

    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid OrderNoteText of length ${value.length}" }
    }

    override fun toString(): String = "OrderNoteText(***)"

    companion object {
        /** From the contract's `text` of `POST /admin/orders/{orderCode}/notes`. */
        const val MAX_LENGTH = 1000

        fun makeNew(raw: String?): Result<OrderNoteText, Failure> {
            val text = raw?.trim()?.lines()?.joinToString("\n") { it.normalizePersianLetters() }?.trim().orEmpty()
            return when {
                text.isEmpty() -> Result.failure(Failure.Required)
                text.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(OrderNoteText(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): OrderNoteText = OrderNoteText(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field = "text", meta = meta) {
        data object Required : Failure("NOTE_REQUIRED")
        data object TooLong : Failure("NOTE_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}
