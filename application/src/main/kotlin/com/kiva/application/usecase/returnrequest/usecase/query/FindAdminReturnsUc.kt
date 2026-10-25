package com.kiva.application.usecase.returnrequest.usecase.query

import com.kiva.application.dto.AdminReturnRequestDto
import com.kiva.application.usecase.returnrequest.AdminReturnRequests
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus
import com.kiva.domain.aggregate.returnrequest.repository.ReturnRequestRepository
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * The panel's return queue (`GET /admin/returns`; ADMIN, OPERATOR): in one status or all of them, newest asked first or
 * oldest (ReturnSorts, the pageable's sort), each row the whole AdminReturnRequest. A status the contract doesn't have is
 * 400 RETURN_STATUS_INVALID on `status`.
 */
class FindAdminReturnsUc(
    private val transactionManager: TransactionManager,
    private val returnRequestRepository: ReturnRequestRepository,
    private val adminReturnRequests: AdminReturnRequests,
    private val shopSettingsRepository: ShopSettingsRepository,
) : QueryUseCase<FindAdminReturnsUc.Qry, Page<AdminReturnRequestDto>, FindAdminReturnsUc.Failure> {

    private val logger = getLogger()

    /** @property status the query parameter as it came; none (or blank) is every status. */
    data class Qry(val pageable: Pageable, val status: String?)

    override suspend fun execute(query: Qry): Result<Page<AdminReturnRequestDto>, Failure> {
        logger.info("FindAdminReturnsUc.execute status=${query.status} page=${query.pageable.page} sort=${query.pageable.sort}")
        val status = when (val raw = query.status?.trim()?.ifEmpty { null }) {
            null -> null
            else -> when (val parsed = ReturnStatus.makeNew(raw)) {
                is Result.Success -> parsed.value
                is Result.Failure -> return Result.failure(Failure.InvalidInput(listOf(parsed.failure)))
            }
        }

        return transactionManager.inTransaction {
            val page = returnRequestRepository.page(status, query.pageable)
            val items = adminReturnRequests.of(page.items, shopSettingsRepository.get())
            Result.success(Page(items, page.page, page.size, page.totalItems))
        }
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) : ResultFailure(code, kind, causes = causes) {
        /** The status filter isn't one (400 on `status`). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }
}
