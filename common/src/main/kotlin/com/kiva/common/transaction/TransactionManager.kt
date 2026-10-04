package com.kiva.common.transaction

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * Runs a piece of work as ONE database transaction: either everything in [inTransaction] is saved, or nothing is.
 *
 * The interface lives in common so use cases can depend on it without knowing which database library is used;
 * the real implementation (Exposed) lives in infrastructure.
 *
 * Rules:
 * - [Result.Success] -> commit.
 * - [Result.Failure] -> rollback. A use case that fails half way must not leave half its writes behind.
 * - Exception -> rollback, and the exception keeps travelling up.
 * - Nested calls JOIN the transaction that is already open. Only the outermost call commits or rolls back.
 *   If an outer block ignores an inner failure and still returns Success, the inner writes are committed too.
 */
interface TransactionManager {
    suspend fun <T, F : ResultFailure> inTransaction(block: suspend () -> Result<T, F>): Result<T, F>
}
