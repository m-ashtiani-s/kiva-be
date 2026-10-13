package com.kiva.application.usecase.address.usecase.query

import com.kiva.application.dto.AddressDto
import com.kiva.application.usecase.address.addressDtosOf
import com.kiva.application.usecase.address.defaultFirst
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.domain.aggregate.geo.repository.GeoRepository

/** The customer's address book (`GET /me/addresses`): the default first, then the rest oldest first. Can't fail. */
class ListMyAddressesUc(
    private val transactionManager: TransactionManager,
    private val addressBookRepository: AddressBookRepository,
    private val geoRepository: GeoRepository,
) : QueryUseCase<ListMyAddressesUc.Qry, List<AddressDto>, Nothing> {

    private val logger = getLogger()

    /** @property userId from the access token, never from the request. */
    data class Qry(val userId: UserId)

    override suspend fun execute(query: Qry): Result<List<AddressDto>, Nothing> {
        logger.info("ListMyAddressesUc.execute userId=${query.userId.value}")
        return transactionManager.inTransaction {
            val book = addressBookRepository.find(query.userId)
            Result.success(geoRepository.addressDtosOf(book.addresses.defaultFirst()))
        }
    }
}
