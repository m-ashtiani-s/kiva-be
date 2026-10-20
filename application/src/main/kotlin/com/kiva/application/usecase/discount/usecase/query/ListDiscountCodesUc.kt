package com.kiva.application.usecase.discount.usecase.query

import com.kiva.application.dto.DiscountCodeDto
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.discount.repository.DiscountCodeRepository

/**
 * The panel's discount codes (`GET /admin/discount-codes`; ADMIN), newest made first. [Qry.active] is the code's switch
 * (`true`/`false`; anything else 400 BOOLEAN_INVALID on `active`), not whether it is in its dates now. The page's uses are
 * counted with one query for the whole page.
 */
class ListDiscountCodesUc(
    private val transactionManager: TransactionManager,
    private val discountCodeRepository: DiscountCodeRepository,
) : QueryUseCase<ListDiscountCodesUc.Qry, Page<DiscountCodeDto>, ListDiscountCodesUc.Failure> {

    private val logger = getLogger()

    data class Qry(val pageable: Pageable, val active: String? = null)

    override suspend fun execute(query: Qry): Result<Page<DiscountCodeDto>, Failure> {
        logger.info("ListDiscountCodesUc.execute page=${query.pageable.page} active=${query.active}")
        val active = when (query.active?.trim()?.ifEmpty { null }) {
            null -> null
            "true" -> true
            "false" -> false
            else -> return Result.failure(Failure.ActiveInvalid)
        }
        return transactionManager.inTransaction {
            val page = discountCodeRepository.list(active, query.pageable)
            val usage = discountCodeRepository.usageStatsOf(page.items.map { it.id })
            Result.success(page.map { DiscountCodeDto.from(it, usage[it.id]) })
        }
    }

    sealed class Failure(code: String, kind: FailureKind, field: String? = null) : ResultFailure(code, kind, field) {
        data object ActiveInvalid : Failure("BOOLEAN_INVALID", FailureKind.INVALID, "active")
    }
}
