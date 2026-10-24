package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel

/**
 * «عکس کیفت رو قبل از ارسال کجا بفرستیم؟»: the messenger and number the shop sends the photo of the packed bag to,
 * kiva's signature step. Required to place an order; a missing channel or a wrong number is the use case's 422
 * (MESSENGER_REQUIRED, MESSENGER_PHONE_INVALID), so they arrive here already typed. Only the free texts are checked here.
 *
 * Personal data: [toString] shows the channel only. When its customer deletes their account it is [erased]: the
 * channel stays (the panel's reports count it), the number and the texts go (roadmap phase 14, decision 2).
 *
 * @property phone the number on that messenger; null only once [erased].
 * @property username the handle on that messenger («@sara_m»), optional ([OptionalText]).
 * @property note «توضیح برای عکس», optional ([OptionalText]).
 */
class PreShipmentMessenger private constructor(
    val channel: MessengerChannel,
    val phone: IranianMobileNumber?,
    val username: String?,
    val note: String?,
) {
    init {
        require(phone != null || (username == null && note == null)) { "An erased messenger keeps no texts" }
        require(username == null || OptionalText.isValid(username, USERNAME_MAX_LENGTH)) { "Invalid messenger username of length ${username?.length}" }
        require(note == null || OptionalText.isValid(note, NOTE_MAX_LENGTH)) { "Invalid messenger note of length ${note?.length}" }
    }

    override fun equals(other: Any?): Boolean =
        other is PreShipmentMessenger && other.channel == channel && other.phone == phone && other.username == username && other.note == note

    override fun hashCode(): Int = listOf(channel, phone, username, note).hashCode()

    override fun toString(): String = "PreShipmentMessenger(channel=$channel)"

    /** Erased with its customer's account (decision 2 of phase 14). */
    val isErased: Boolean get() = phone == null

    /** The channel alone; erasing it again changes nothing. */
    fun erased(): PreShipmentMessenger = PreShipmentMessenger(channel, null, null, null)

    companion object {
        /** From the contract's PlaceOrderRequest.preShipmentMessenger. */
        const val USERNAME_MAX_LENGTH = 50
        const val NOTE_MAX_LENGTH = 200

        /** Both texts at once, so the form shows both mistakes together; each names its own field. */
        fun makeNew(channel: MessengerChannel, phone: IranianMobileNumber, username: String?, note: String?): Result<PreShipmentMessenger, Invalid> =
            textsOf(username, note).mapOnSuccess { (checkedUsername, checkedNote) -> PreShipmentMessenger(channel, phone, checkedUsername, checkedNote) }

        /**
         * The free texts alone, checked exactly as [makeNew] checks them: placing an order answers a wrong text (400)
         * before a missing channel or a wrong number (422), so it checks them before it has those.
         */
        fun checkTexts(username: String?, note: String?): Result<Unit, Invalid> = textsOf(username, note).mapOnSuccess { }

        private fun textsOf(username: String?, note: String?): Result<Pair<String?, String?>, Invalid> {
            val problems = mutableListOf<ResultFailure>()
            val checkedUsername = when (val checked = OptionalText.check(username, USERNAME_MAX_LENGTH)) {
                is OptionalText.Checked.Fine -> checked.value
                OptionalText.Checked.TooLong -> null.also { problems += Failure.UsernameTooLong }
                OptionalText.Checked.InvalidCharacters -> null.also { problems += Failure.UsernameInvalid }
            }
            val checkedNote = when (val checked = OptionalText.check(note, NOTE_MAX_LENGTH)) {
                is OptionalText.Checked.Fine -> checked.value
                OptionalText.Checked.TooLong -> null.also { problems += Failure.NoteTooLong }
                OptionalText.Checked.InvalidCharacters -> null.also { problems += Failure.NoteInvalid }
            }
            return if (problems.isEmpty()) Result.success(checkedUsername to checkedNote) else Result.failure(Invalid(problems))
        }

        /** For values read back from storage (an erased one without its [phone]). Still checked by `init`. */
        fun constructFromPersisted(channel: MessengerChannel, phone: IranianMobileNumber?, username: String?, note: String?): PreShipmentMessenger =
            PreShipmentMessenger(channel, phone, username, note)
    }

    /** One or both texts are wrong; each cause names its field (400 VALIDATION_ERROR). */
    data class Invalid(val problems: List<ResultFailure>) : ResultFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

    // The fields of the contract's preShipmentMessenger; the caller puts them under `preShipmentMessenger.`.
    sealed class Failure(code: String, field: String, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, FailureKind.INVALID, field = field, meta = meta) {
        data object UsernameTooLong : Failure("MESSENGER_USERNAME_TOO_LONG", "username", mapOf("maxLength" to USERNAME_MAX_LENGTH))
        data object UsernameInvalid : Failure("MESSENGER_USERNAME_INVALID", "username")
        data object NoteTooLong : Failure("MESSENGER_NOTE_TOO_LONG", "note", mapOf("maxLength" to NOTE_MAX_LENGTH))
        data object NoteInvalid : Failure("MESSENGER_NOTE_INVALID", "note")
    }
}
