package com.kiva.application.usecase.idempotency

import com.kiva.application.port.IdempotencyStore
import com.kiva.application.port.IdempotentRequest
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import java.time.Clock

/**
 * The one way a use case that takes an `Idempotency-Key` runs its work (roadmap section 12), so placing an order and
 * the two payment retries can't each get it slightly wrong. Shared orchestration of those use cases (Rule 15).
 *
 * **Inside the use case's transaction**, which must already be open:
 * 1. the key is locked: a second request with it waits for the first to commit;
 * 2. used before for this very request: the work is not done again, and the use case answers from what it made
 *    ([Run.Replayed]); used for another request: [Run.KeyReused], the use case's 409;
 * 3. never used: the work runs; if it succeeds, its record is written last in the same transaction. If it fails,
 *    nothing is written and the transaction rolls back, so a repeat runs again.
 */
class IdempotencyGuard(private val store: IdempotencyStore, private val clock: Clock) {

    private val logger = getLogger()

    /** How a request with a key went. */
    sealed interface Run<out T> {
        /** The work ran now. */
        data class Done<T>(val value: T) : Run<T>

        /** The work ran before for this very request; [outcome] is what it made then. */
        data class Replayed(val outcome: Map<String, String>) : Run<Nothing>

        /** The key was used before for another request. */
        data object KeyReused : Run<Nothing>
    }

    /**
     * Runs [work] once for [request]. [outcomeOf] tells what the work made, by id (`orderCode`, `paymentId`): what a
     * repeat answers from. A failure of [work] comes back as it is, and records nothing.
     */
    suspend fun <T, F : ResultFailure> once(
        request: IdempotentRequest,
        outcomeOf: (T) -> Map<String, String>,
        work: suspend () -> Result<T, F>,
    ): Result<Run<T>, F> {
        when (val earlier = store.lock(request)) {
            is IdempotencyStore.Earlier.SameRequest -> {
                logger.info("IdempotencyGuard: ${request.scope} of user ${request.userId.value} repeated; answered from ${earlier.outcome}")
                return Result.success(Run.Replayed(earlier.outcome))
            }
            IdempotencyStore.Earlier.OtherRequest -> {
                logger.info("IdempotencyGuard: ${request.scope} of user ${request.userId.value} reused a key for another request")
                return Result.success(Run.KeyReused)
            }
            IdempotencyStore.Earlier.Unused -> Unit
        }
        val value = work().unwrapOr { return Result.failure(it) }
        store.record(request, outcomeOf(value), clock.instant())
        return Result.success(Run.Done(value))
    }
}
