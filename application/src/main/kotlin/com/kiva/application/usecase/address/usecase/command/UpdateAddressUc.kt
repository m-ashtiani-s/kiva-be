package com.kiva.application.usecase.address.usecase.command

import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.address.addressIdOf
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
 * Replaces an address of the customer's book (`PUT /me/addresses/{addressId}`). `setAsDefault` true makes it the
 * default; false never takes that away. Orders placed with it keep their own copy, so they don't change.
 */
class UpdateAddressUc(
    private val transactionManager: TransactionManager,
    private val addressBookDomainService: AddressBookDomainService,
    private val addressBookRepository: AddressBookRepository,
    private val clock: Clock,
) : CommandUseCase<UpdateAddressUc.Cmd, AddressId, UpdateAddressUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [addressId] the path segment as it arrived. */
    data class Cmd(val userId: UserId, val addressId: String, val address: AddressFields, val setAsDefault: Boolean?)

    override suspend fun execute(command: Cmd): Result<AddressId, Failure> {
        logger.info("UpdateAddressUc.execute userId=${command.userId.value} addressId=${command.addressId} setAsDefault=${command.setAsDefault}")

        // Before the transaction: a segment that names no address, then every field at once.
        val addressId = addressIdOf(command.addressId) ?: return Result.failure(Failure.AddressNotFound)
        val input = command.address.toInput().unwrapOr { return Result.failure(Failure.InvalidInput(it.problems)) }

        return transactionManager.inTransaction {
            val book = addressBookRepository.lockAndFind(command.userId)
            addressBookDomainService.updateAddress(book, addressId, input, command.setAsDefault ?: false, clock.instant())
                .mapOnFailure(::failureOf)
                .unwrapOr { return@inTransaction Result.failure(it) }
            addressBookRepository.save(book)
            Result.success(addressId)
        }
    }

    private fun failureOf(failure: AddressBookDomainService.Failure): Failure = when (failure) {
        AddressBookDomainService.Failure.ProvinceNotFound -> Failure.ProvinceNotFound
        AddressBookDomainService.Failure.CityNotFound -> Failure.CityNotFound
        AddressBookDomainService.Failure.CityNotInProvince -> Failure.CityNotInProvince
        is AddressBookDomainService.Failure.Book -> when (failure.failure) {
            AddressBook.Failure.AddressNotFound -> Failure.AddressNotFound
            AddressBook.Failure.LimitReached -> error("Editing never adds an address")
        }
    }

    sealed class Failure(
        code: String,
        kind: FailureKind,
        field: String? = null,
        causes: List<ResultFailure> = emptyList(),
    ) : ResultFailure(code, kind, field, causes) {
        /** One or more fields are wrong; each cause names its field (400 VALIDATION_ERROR). */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)

        /** Not in this customer's book: someone else's, or nobody's (404 either way). */
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.NOT_FOUND)

        data object ProvinceNotFound : Failure("PROVINCE_NOT_FOUND", FailureKind.INVALID, "provinceId")

        data object CityNotFound : Failure("CITY_NOT_FOUND", FailureKind.INVALID, "cityId")

        data object CityNotInProvince : Failure("CITY_NOT_IN_PROVINCE", FailureKind.BUSINESS_RULE)
    }
}
