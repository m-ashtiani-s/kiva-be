package com.kiva.application.usecase.account.usecase.query

import com.kiva.application.dto.UserDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.repository.UserRepository

/**
 * The logged-in user's own account (`GET /me`, and the `user` of the login response).
 *
 * The id comes from the access token, never from the request: nobody reads another person's account through this.
 */
class GetMeUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
) : QueryUseCase<GetMeUc.Qry, UserDto, GetMeUc.Failure> {

    private val logger = getLogger()

    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<UserDto, Failure> {
        logger.info("GetMeUc.execute user=${query.userId.value}")
        return transactionManager.inTransaction {
            val user = userRepository.findById(query.userId) ?: return@inTransaction Result.failure(Failure.AccountGone)
            Result.success(UserDto.from(user))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** A token of an account that no longer exists: there is no "me" to show, so log in again. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)
    }
}
