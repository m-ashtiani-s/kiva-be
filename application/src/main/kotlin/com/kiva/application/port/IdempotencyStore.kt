package com.kiva.application.port

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnSuccess
import com.kiva.common.valueobject.UserId
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The keys of `Idempotency-Key` (roadmap section 12), kept **in the use case's own transaction**: the work and the
 * record of it commit together, or neither does. A record written after the commit would leave a moment in which a
 * repeat of the request finds nothing and does the work again; a failure that rolls back leaves no record, so its
 * repeat runs again, which is right: the customer may have fixed what was wrong.
 *
 * It is a port, not an HTTP middleware, for that reason: only the use case's transaction can hold both.
 */
interface IdempotencyStore {

    /**
     * Locks [request]'s key (the customer, the scope, the key) until the transaction ends, then tells what the key was
     * used for before. A second request with the same key waits here until the first commits or rolls back, so the
     * work is never done twice at the same moment.
     */
    fun lock(request: IdempotentRequest): Earlier

    /** Records that [request] was done and what it made ([outcome]), in the same transaction as the work. */
    fun record(request: IdempotentRequest, outcome: Map<String, String>, now: Instant)

    /** What a key was used for before. */
    sealed interface Earlier {
        /** Never: do the work. */
        data object Unused : Earlier

        /** For this very request: don't do it again; answer from what it made. */
        data class SameRequest(val outcome: Map<String, String>) : Earlier

        /** For another request (another body, another path): 409 IDEMPOTENCY_KEY_REUSED. */
        data object OtherRequest : Earlier
    }
}

/**
 * One request that takes an `Idempotency-Key`: whose, which kind, its key, and its fingerprint. A key belongs to its
 * customer: another customer's identical key is another key.
 */
data class IdempotentRequest(val userId: UserId, val scope: IdempotencyScope, val key: IdempotencyKey, val requestHash: RequestHash) {
    companion object {
        /** [rawKey] as the header arrived; a missing or malformed one is 400 on `Idempotency-Key`. */
        fun makeNew(userId: UserId, scope: IdempotencyScope, rawKey: String?, requestHash: RequestHash): Result<IdempotentRequest, IdempotencyKey.Failure> =
            IdempotencyKey.makeNew(rawKey).mapOnSuccess { IdempotentRequest(userId, scope, it, requestHash) }
    }
}

/** Which kind of request a key is for. Stored with it, so a value in use is never renamed. */
enum class IdempotencyScope {
    /** `POST /orders` */
    PLACE_ORDER,

    /** `POST /payments/{paymentId}/retry` */
    RETRY_PAYMENT,

    /** `POST /me/orders/{orderCode}/payments` */
    PAY_ORDER,
}

/**
 * The `Idempotency-Key` header: a UUID the client makes for each attempt, and sends again only to repeat that very
 * request (a double click, a lost answer). Kept in lower case, so `A1B2…` and `a1b2…` are one key.
 */
@JvmInline
value class IdempotencyKey private constructor(val value: UUID) {

    companion object {
        // The canonical 8-4-4-4-12 form only: UUID.fromString would also take "1-2-3-4-5".
        private val FORMAT = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

        fun makeNew(raw: String?): Result<IdempotencyKey, Failure> {
            val trimmed = raw?.trim()
            return when {
                trimmed.isNullOrEmpty() -> Result.failure(Failure.Required)
                !FORMAT.matches(trimmed) -> Result.failure(Failure.Invalid)
                else -> Result.success(IdempotencyKey(UUID.fromString(trimmed.lowercase())))
            }
        }

        /** For values read back from storage. */
        fun constructFromPersisted(value: UUID): IdempotencyKey = IdempotencyKey(value)

        /** The header's name, as the contract and its field errors call it. */
        const val HEADER = "Idempotency-Key"
    }

    // Always the header's own name, as the contract says: `errors[].field = "Idempotency-Key"`.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = HEADER) {
        data object Required : Failure("IDEMPOTENCY_KEY_REQUIRED")
        data object Invalid : Failure("IDEMPOTENCY_KEY_INVALID")
    }
}

/**
 * The fingerprint of a request: SHA-256 of its canonical form (the HTTP layer writes that: body and path, keys in
 * order, no spaces). The same key with another fingerprint is another request.
 */
class RequestHash private constructor(private val bytes: ByteArray) {

    init {
        require(bytes.size == LENGTH) { "A request hash is $LENGTH bytes, not ${bytes.size}" }
    }

    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is RequestHash && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    // The canonical request may hold an address or a phone; its hash says nothing, but there is no reason to print it.
    override fun toString(): String = "RequestHash(****)"

    companion object {
        const val LENGTH = 32

        /** The SHA-256 of [canonicalRequest], as UTF-8. */
        fun of(canonicalRequest: String): RequestHash = RequestHash(MessageDigest.getInstance("SHA-256").digest(canonicalRequest.toByteArray(Charsets.UTF_8)))

        fun constructFromPersisted(bytes: ByteArray): RequestHash = RequestHash(bytes.copyOf())
    }
}
