package com.kiva.application.usecase.address.usecase.command

import com.kiva.application.usecase.address.AddressFields
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.address.entity.AddressBook
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import java.time.Clock

/**
 * Adds an address to the customer's book (`POST /me/addresses`). The first address is the default; a later one only
 * with `setAsDefault`. The book is locked for the whole change, so two adds at once can't pass the limit of 20 together.
 */
class AddAddressUc(
    private val transactionManager: TransactionManager,
    private val addressBookDomainService: AddressBookDomainService,
    private val addressBookRepository: AddressBookRepository,
    private val clock: Clock,
) : CommandUseCase<AddAddressUc.Cmd, AddressId, AddAddressUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token, never from the request. */
    data class Cmd(val userId: UserId, val address: AddressFields, val setAsDefault: Boolean?)

    override suspend fun execute(command: Cmd): Result<AddressId, Failure> {
        logger.info("AddAddressUc.execute userId=${command.userId.value} setAsDefault=${command.setAsDefault}")

        // Every field at once, before the transaction, so bad input never takes a database connection.
        val input = command.address.toInput().unwrapOr { return Result.failure(Failure.InvalidInput(it.problems)) }

        return transactionManager.inTransaction {
            val book = addressBookRepository.lockAndFind(command.userId)
            val address = addressBookDomainService.addAddress(book, input, command.setAsDefault ?: false, clock.instant())
                .mapOnFailure(::failureOf)
                .unwrapOr { return@inTransaction Result.failure(it) }
            addressBookRepository.save(book)
            Result.success(address.id)
        }
    }

    private fun failureOf(failure: AddressBookDomainService.Failure): Failure = when (failure) {
        AddressBookDomainService.Failure.ProvinceNotFound -> Failure.ProvinceNotFound
        AddressBookDomainService.Failure.CityNotFound -> Failure.CityNotFound
        AddressBookDomainService.Failure.CityNotInProvince -> Failure.CityNotInProvince
        is AddressBookDomainService.Failure.Book -> when (failure.failure) {
            AddressBook.Failure.LimitReached -> Failure.LimitReached
            AddressBook.Failure.AddressNotFound -> error("Adding never looks for an existing address")
        }
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
        meta: Map<String, Any> = emptyMap(),
    ) : ResultFailure(code, kind, field, causes, meta) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        data object ProvinceNotFound : Failure("PROVINCE_NOT_FOUND", FailureKind.INVALID, "provinceId")

        data object CityNotFound : Failure("CITY_NOT_FOUND", FailureKind.INVALID, "cityId")

        data object CityNotInProvince : Failure("CITY_NOT_IN_PROVINCE", FailureKind.BUSINESS_RULE)

        data object LimitReached :
            Failure("ADDRESS_LIMIT_REACHED", FailureKind.BUSINESS_RULE, meta = mapOf("maxAddresses" to AddressBook.MAX_ADDRESSES))
    }
}
