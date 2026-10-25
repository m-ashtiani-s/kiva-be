package com.kiva.application.usecase.returnrequest.usecase.query

import com.kiva.application.dto.ReturnRequestDto
import com.kiva.application.usecase.returnrequest.ReturnRequests
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * One of the customer's returns, as «مرجوعی‌های من» shows it: what `POST /me/orders/{orderCode}/returns` answers with
 * (Rule 6: the command returns the id, the route reads it here). Someone else's or none is RETURN_NOT_FOUND.
 */
class GetMyReturnUc(
    private val transactionManager: TransactionManager,
    private val returnRequestRepository: ReturnRequestRepository,
    private val returnRequests: ReturnRequests,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<GetMyReturnUc.Qry, ReturnRequestDto, GetMyReturnUc.Failure> {

    private val logger = getLogger()

    data class Qry(val userId: UserId, val returnId: ReturnId)

    override suspend fun execute(query: Qry): Result<ReturnRequestDto, Failure> {
        logger.info("GetMyReturnUc.execute userId=${query.userId.value} returnId=${query.returnId.value}")
        return transactionManager.inTransaction {
            val ret = returnRequestRepository.find(query.returnId)?.takeIf { it.userId == query.userId }
                ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
            Result.success(returnRequests.of(listOf(ret), shopSettingsRepository.get()).single())
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
