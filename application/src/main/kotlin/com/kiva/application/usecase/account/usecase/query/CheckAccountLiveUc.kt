package com.kiva.application.usecase.account.usecase.query

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.repository.UserRepository

/**
 * Whether the account of a valid access token is still there and not deleted, asked by the access token check on every
 * logged-in request (roadmap phase 14, decision 10). Deleting an account ends its sessions, but an access token lives up
 * to 15 minutes on its own: without this, the token of a deleted account would go on working until it expires.
 *
 * One column of one row. No log line: it runs for every request, and the request is logged already.
 */
class CheckAccountLiveUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
) : QueryUseCase<CheckAccountLiveUc.Qry, Unit, CheckAccountLiveUc.Failure> {

    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<Unit, Failure> =
        transactionManager.inTransaction {
            when (userRepository.isDeleted(query.userId)) {
                false -> Result.success(Unit)
                true, null -> Result.failure(Failure.AccountGone)
            }
        }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Deleted, or never there: the token is refused (401 UNAUTHORIZED). */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)
    }
}
