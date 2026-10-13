package com.kiva.application.usecase.address.usecase.query

import com.kiva.application.dto.AddressDto
import com.kiva.application.usecase.address.addressDtosOf
import com.kiva.application.usecase.address.addressIdOf
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
 * One address of the customer's book (`GET /me/addresses/{addressId}`, and the answer after adding or editing one).
 * Someone else's address is ADDRESS_NOT_FOUND, exactly like one that doesn't exist: nobody learns it is there.
 */
class GetMyAddressUc(
    private val transactionManager: TransactionManager,
    private val addressBookRepository: AddressBookRepository,
    private val geoRepository: GeoRepository,
) : QueryUseCase<GetMyAddressUc.Qry, AddressDto, GetMyAddressUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [addressId] the path segment as it arrived. */
    data class Qry(val userId: UserId, val addressId: String)

    override suspend fun execute(query: Qry): Result<AddressDto, Failure> {
        logger.info("GetMyAddressUc.execute userId=${query.userId.value} addressId=${query.addressId}")
        val addressId = addressIdOf(query.addressId) ?: return Result.failure(Failure.AddressNotFound)

        return transactionManager.inTransaction {
            val address = addressBookRepository.find(query.userId).find(addressId)
                ?: return@inTransaction Result.failure(Failure.AddressNotFound)
            Result.success(geoRepository.addressDtosOf(listOf(address)).single())
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
