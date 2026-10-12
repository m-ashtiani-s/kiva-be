package com.kiva.application.usecase.admin.usecase.query

import com.kiva.application.dto.AdminCustomerDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.AdminCustomerRepository.CustomerSearch
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.text.normalizePersian
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.IranianMobileNumber

/**
 * The panel's customer list (`GET /admin/customers`, ADMIN and SUPPORT): everyone, or those matching `q`, newest
 * account first, each with what they bought (their paid orders, the money, the latest one).
 *
 * `q` is read the way people type it: Persian or Arabic digits and letters are made the same as stored ones. A whole
 * mobile number in any form (`+98 912 …`, `۰۹۱۲…`) finds exactly that phone; otherwise every word must be in the phone,
 * the first name or the last name (`0912` finds every phone with it, «سارا محمدی» finds her).
 */
class FindCustomersUc(
    private val transactionManager: TransactionManager,
    private val adminCustomerRepository: AdminCustomerRepository,
    private val orderQmRepository: OrderQmRepository,
) : QueryUseCase<FindCustomersUc.Qry, Page<AdminCustomerDto>, FindCustomersUc.Failure> {

    private val logger = getLogger()

    data class Qry(val pageable: Pageable, val q: String? = null)

    override suspend fun execute(query: Qry): Result<Page<AdminCustomerDto>, Failure> {
        logger.info("FindCustomersUc.execute q=${if (query.q.isNullOrBlank()) "none" else "given"} page=${query.pageable.page}")
        val search = searchOf(query.q) ?: return Result.failure(Failure.InvalidInput(listOf(QueryTooLong)))

        return transactionManager.inTransaction {
            val customers = adminCustomerRepository.find(search, query.pageable)
            // One query for the whole page's purchases, not one per customer.
            val purchases = orderQmRepository.purchasesOf(customers.items.map { it.id })
            Result.success(customers.map { AdminCustomerDto.from(it, purchases[it.id]) })
        }
    }

    /** Null when [raw] is too long to be a search. */
    private fun searchOf(raw: String?): CustomerSearch? {
        val text = raw?.normalizePersian().orEmpty()
        if (text.length > MAX_QUERY_LENGTH) return null
        if (text.isEmpty()) return CustomerSearch.EVERYONE
        IranianMobileNumber.makeNew(text).getOrNull()?.let { return CustomerSearch(phone = it) }
        return CustomerSearch(words = text.lowercase().split(' ').filter { it.isNotEmpty() })
    }

    /** `q` is longer than [MAX_QUERY_LENGTH]. */
    data object QueryTooLong :
        ResultFailure("SEARCH_QUERY_TOO_LONG", FailureKind.INVALID, field = "q", meta = mapOf("maxLength" to MAX_QUERY_LENGTH))

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        const val MAX_QUERY_LENGTH = 100
    }
}
