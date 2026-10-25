package com.kiva.application.usecase.discount.usecase.query

import com.kiva.application.dto.DiscountCodeDto
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository

/** One discount code as the panel shows it, with its uses: the answer of making or editing one. */
class GetDiscountCodeUc(
    private val transactionManager: TransactionManager,
    private val discountCodeRepository: DiscountCodeRepository,
) : QueryUseCase<GetDiscountCodeUc.Qry, DiscountCodeDto, GetDiscountCodeUc.Failure> {

    private val logger = getLogger()

    data class Qry(val id: DiscountCodeId)

    override suspend fun execute(query: Qry): Result<DiscountCodeDto, Failure> {
        logger.info("GetDiscountCodeUc.execute id=${query.id.value}")
        return transactionManager.inTransaction {
            val code = discountCodeRepository.findById(query.id) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(DiscountCodeDto.from(code, discountCodeRepository.usageStatsOf(listOf(code.id))[code.id]))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
