package com.kiva.application.usecase.admin.usecase.query

import com.kiva.application.dto.AdminCustomerDetailDto
import com.kiva.application.dto.AdminCustomerDto
import com.kiva.application.repository.AdminCustomerRepository
import com.kiva.application.repository.OrderQmRepository
import com.kiva.application.usecase.address.addressDtosOf
import com.kiva.application.usecase.address.defaultFirst
import com.kiva.application.usecase.order.AdminOrderItems
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.geo.repository.GeoRepository

/**
 * A customer's page in the panel (`GET /admin/customers/{customerId}`; ADMIN, SUPPORT): the profile, every address of
 * the book (the default first, written out as the book writes them), and the last [RECENT_ORDERS] orders in any status
 * (roadmap phase 4, decision 16). An id that isn't a number, or that nobody has, is 404. Reading is not logged (the
 * contract), and nothing personal goes into the application log: ids only.
 */
class GetCustomerDetailUc(
    private val transactionManager: TransactionManager,
    private val adminCustomerRepository: AdminCustomerRepository,
    private val orderQmRepository: OrderQmRepository,
    private val addressBookRepository: AddressBookRepository,
    private val geoRepository: GeoRepository,
) : QueryUseCase<GetCustomerDetailUc.Qry, AdminCustomerDetailDto, GetCustomerDetailUc.Failure> {

    private val logger = getLogger()

    /** @property customerId the path's `{customerId}` as it came. */
    data class Qry(val customerId: String)

    override suspend fun execute(query: Qry): Result<AdminCustomerDetailDto, Failure> {
        logger.info("GetCustomerDetailUc.execute customer=${query.customerId}")
        val id = query.customerId.toLongOrNull()?.let { UserId.makeNew(it).getOrNull() } ?: return Result.failure(Failure.NotFound)

        return transactionManager.inTransaction {
            val user = adminCustomerRepository.findById(id) ?: return@inTransaction Result.failure(Failure.NotFound)
            val addresses = geoRepository.addressDtosOf(addressBookRepository.find(user.id).addresses.defaultFirst())
            val recent = orderQmRepository.recentOfCustomer(user.id, RECENT_ORDERS).map { AdminOrderItems.itemOf(it, user) }
            Result.success(
                AdminCustomerDetailDto(
                    customer = AdminCustomerDto.from(user, orderQmRepository.purchasesOf(listOf(user.id))[user.id]),
                    firstName = user.firstName?.value,
                    lastName = user.lastName?.value,
                    birthDate = user.birthDate?.value,
                    gender = user.gender,
                    defaultMessengerPhone = user.defaultMessengerPhone?.value,
                    addresses = addresses,
                    recentOrders = recent,
                ),
            )
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object NotFound : Failure("NOT_FOUND", FailureKind.NOT_FOUND)
    }

    companion object {
        /** How many of the customer's orders the page shows; the rest are in `GET /admin/orders?q=<phone>`. */
        const val RECENT_ORDERS = 5
    }
}
