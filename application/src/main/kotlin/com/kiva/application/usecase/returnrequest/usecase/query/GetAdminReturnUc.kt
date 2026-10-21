package com.kiva.application.usecase.returnrequest.usecase.query

import com.kiva.application.dto.AdminReturnRequestDto
import com.kiva.application.usecase.returnrequest.AdminReturnRequests
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * One return as the panel sees it: what every step staff take on it answers with (Rule 6: the command returns nothing,
 * the route reads the return here). An id that can't be one, or nobody's, is RETURN_NOT_FOUND.
 */
class GetAdminReturnUc(
    private val transactionManager: TransactionManager,
    private val returnRequestRepository: ReturnRequestRepository,
    private val adminReturnRequests: AdminReturnRequests,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<GetAdminReturnUc.Qry, AdminReturnRequestDto, GetAdminReturnUc.Failure> {

    private val logger = getLogger()

    /** @property returnId the path parameter as it came. */
    data class Qry(val returnId: String)

    override suspend fun execute(query: Qry): Result<AdminReturnRequestDto, Failure> {
        val id = ReturnId.makeNew(query.returnId).getOrNull()
        logger.info("GetAdminReturnUc.execute returnId=${id?.value ?: "(not an id)"}")
        if (id == null) return Result.failure(Failure.ReturnNotFound)
        return transactionManager.inTransaction {
            val ret = returnRequestRepository.find(id) ?: return@inTransaction Result.failure(Failure.ReturnNotFound)
            Result.success(adminReturnRequests.of(ret, shopSettingsRepository.get()))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object ReturnNotFound : Failure("RETURN_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
