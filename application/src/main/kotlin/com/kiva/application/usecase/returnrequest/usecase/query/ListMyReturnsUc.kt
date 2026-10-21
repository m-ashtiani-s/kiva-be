package com.kiva.application.usecase.returnrequest.usecase.query

import com.kiva.application.dto.ReturnRequestDto
import com.kiva.application.usecase.returnrequest.ReturnRequests
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * «مرجوعی‌های من» (`GET /me/returns`): every return of the customer, REJECTED ones too, newest first, at most [LIMIT]: a
 * plain list, not a page (the contract's). Each with the names and colors its lines were bought with (ReturnRequests).
 */
class ListMyReturnsUc(
    private val transactionManager: TransactionManager,
    private val returnRequestRepository: ReturnRequestRepository,
    private val returnRequests: ReturnRequests,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<ListMyReturnsUc.Qry, List<ReturnRequestDto>, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token, never from the request. */
    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<List<ReturnRequestDto>, Nothing> {
        logger.info("ListMyReturnsUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            Result.success(returnRequests.of(returnRequestRepository.findOfUser(query.userId, LIMIT), shopSettingsRepository.get()))
        }
    }

    companion object {
        const val LIMIT = 50
    }
}
