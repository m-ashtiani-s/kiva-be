package com.kiva.application.util

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager

/**
 * Runs the block directly, without a database, so use cases can be tested with in-memory repositories or mocks.
 * Counts the transactions opened, to check "one transaction per use case" and "bad input opens none".
 */
class MockTransactionManager : TransactionManager {
    var transactionsOpened = 0
        private set

    override suspend fun <T, F : ResultFailure> inTransaction(block: suspend () -> Result<T, F>): Result<T, F> {
        transactionsOpened++
        return block()
    }
}
