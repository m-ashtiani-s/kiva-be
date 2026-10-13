package com.kiva.application.usecase.admin.usecase.query

import com.kiva.application.dto.AdminCustomerDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.OrderQmRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId

/** One customer as the panel shows them: the answer of a change made to them in the panel (their roles). */
class GetCustomerUc(
    private val transactionManager: TransactionManager,
    private val adminCustomerRepository: AdminCustomerRepository,
    private val orderQmRepository: OrderQmRepository,
) : QueryUseCase<GetCustomerUc.Qry, AdminCustomerDto, GetCustomerUc.Failure> {

    private val logger = getLogger()

    data class Qry(val customerId: UserId)

    override suspend fun execute(query: Qry): Result<AdminCustomerDto, Failure> {
        logger.info("GetCustomerUc.execute customer=${query.customerId.value}")
        return transactionManager.inTransaction {
            val user = adminCustomerRepository.findById(query.customerId) ?: return@inTransaction Result.failure(Failure.NotFound)
            Result.success(AdminCustomerDto.from(user, orderQmRepository.purchasesOf(listOf(user.id))[user.id]))
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
