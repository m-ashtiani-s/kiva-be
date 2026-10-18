package com.kiva.domain.aggregate.refund.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizeDigits
import com.kiva.common.text.normalizePersianLetters

/**
 * How the money goes back, by hand (roadmap section 1, rows 3, 4 and 6): to the card it was paid with, by a card-to-card
 * transfer ([ORIGINAL_PAYMENT]), or to the IBAN the customer gave on the phone ([BANK_TRANSFER]), which the shop never
 * stores. There is no store credit.
 */
enum class RefundMethod {
    ORIGINAL_PAYMENT,
    BANK_TRANSFER,
    ;

    companion object {
        /** As the contract sends it, exactly; anything else (STORE_CREDIT included) is REFUND_METHOD_INVALID. */
        fun makeNew(raw: String): Result<RefundMethod, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "method") {
        data object Invalid : Failure("REFUND_METHOD_INVALID")
    }
}

/**
 * Where a refund is (the contract's `Refund.status`):
 *
 * ```
 * PENDING ─→ PROCESSING ─┬─→ COMPLETED (the bank's reference)
 *    └───────────────────┴─→ FAILED
 * ```
 *
 * [COMPLETED] and [FAILED] are final: a failed refund is opened again as a new one.
 */
enum class RefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    ;

    /** Done, one way or the other: nothing moves it any more. */
    val isFinal: Boolean get() = this == COMPLETED || this == FAILED

    companion object {
        /** A status `PATCH /admin/refunds/{id}` may ask for: PROCESSING, COMPLETED or FAILED; anything else is REFUND_STATUS_INVALID. */
        fun makeNewTarget(raw: String): Result<RefundStatus, Failure> =
            entries.firstOrNull { it.name == raw && it != PENDING }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "status") {
        data object Invalid : Failure("REFUND_STATUS_INVALID")
    }
}

/** Why a refund was opened: staff by hand, a cancellation, a change request (phase 3) or a return (phase 7). */
enum class RefundSource {
    MANUAL,
    CANCELLATION,
    CHANGE_REQUEST,
    RETURN,
}

/** Why staff give money back (`reason` of `POST …/refunds`): required, up to [MAX_LENGTH]. Shown to the panel only. */
@JvmInline
value class RefundReason private constructor(val value: String) {

    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid RefundReason of length ${value.length}" }
    }

    override fun toString(): String = "RefundReason(***)"

    companion object {
        const val MAX_LENGTH = 300

        fun makeNew(raw: String?): Result<RefundReason, Failure> {
            val text = raw?.normalizePersianLetters()?.trim().orEmpty()
            return when {
                text.isEmpty() -> Result.failure(Failure.Required)
                text.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(RefundReason(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): RefundReason = RefundReason(value)
    }

    sealed class Failure(code: String, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, FailureKind.INVALID, field = "reason", meta = meta) {
        data object Required : Failure("REFUND_REASON_REQUIRED")
        data object TooLong : Failure("REFUND_REASON_TOO_LONG", mapOf("maxLength" to MAX_LENGTH))
    }
}

/**
 * The bank's reference number of the transfer that gave the money back (`referenceId`): up to [MAX_LENGTH] Latin letters,
 * digits and dashes; Persian digits are taken as Latin. A financial reference: never in a log, nor in the change log.
 */
@JvmInline
value class BankReference private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid BankReference of length ${value.length}" }
    }

    override fun toString(): String = "BankReference(***)"

    companion object {
        const val MAX_LENGTH = 64
        private val FORMAT = Regex("^[0-9A-Za-z-]{1,$MAX_LENGTH}$")

        /** Missing or blank means none (null); the rule that COMPLETED needs one is the refund's. */
        fun makeNew(raw: String?): Result<BankReference?, Failure> {
            val text = raw?.trim()?.normalizeDigits().orEmpty()
            return when {
                text.isEmpty() -> Result.success(null)
                FORMAT.matches(text) -> Result.success(BankReference(text))
                else -> Result.failure(Failure.Invalid)
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): BankReference = BankReference(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "referenceId", meta = mapOf("maxLength" to MAX_LENGTH)) {
        data object Invalid : Failure("REFERENCE_ID_INVALID")
    }
}

/**
 * The note of a refund's last change (`note` of `PATCH /admin/refunds/{id}`, the contract's `statusNote`), which the
 * customer sees too («شماره شبا تأیید نشد؛ دوباره باهات تماس می‌گیریم»). Optional, up to [MAX_LENGTH].
 */
@JvmInline
value class RefundNote private constructor(val value: String) {

    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "Invalid RefundNote of length ${value.length}" }
    }

    override fun toString(): String = "RefundNote(***)"

    companion object {
        const val MAX_LENGTH = 300

        /** Missing or blank means no note (null). */
        fun makeNew(raw: String?): Result<RefundNote?, Failure> {
            val text = raw?.normalizePersianLetters()?.trim().orEmpty()
            return when {
                text.isEmpty() -> Result.success(null)
                text.length > MAX_LENGTH -> Result.failure(Failure.TooLong)
                else -> Result.success(RefundNote(text))
            }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): RefundNote = RefundNote(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "note", meta = mapOf("maxLength" to MAX_LENGTH)) {
        data object TooLong : Failure("REFUND_NOTE_TOO_LONG")
    }
}
