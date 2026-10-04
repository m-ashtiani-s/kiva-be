package com.kiva.infrastructure.common.persistence

import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager as ExposedTransactions

class ExposedTransactionManager(private val database: Database) : TransactionManager {

    override suspend fun <T, F : ResultFailure> inTransaction(block: suspend () -> Result<T, F>): Result<T, F> {
        // Already inside a transaction: join it. The outermost call decides commit or rollback.
        if (ExposedTransactions.currentOrNull() != null) return block()

        // JDBC blocks the thread while it waits for the database, so keep it off Ktor's request threads.
        return withContext(Dispatchers.IO) {
            suspendTransaction(database) {
                val result = block()
                if (result is Result.Failure) rollback()
                result
            }
        }
    }
}
