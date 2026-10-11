package com.kiva.application.usecase.address.usecase.command

import com.kiva.application.usecase.address.addressIdOf
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import java.time.Clock

/** Makes an address the default (`POST /me/addresses/{addressId}/default`); the old default stops being one. */
class SetDefaultAddressUc(
    private val transactionManager: TransactionManager,
    private val addressBookRepository: AddressBookRepository,
    private val clock: Clock,
) : CommandUseCase<SetDefaultAddressUc.Cmd, Unit, SetDefaultAddressUc.Failure> {

    private val logger = getLogger()

    /** @property userId from the access token; [addressId] the path segment as it arrived. */
    data class Cmd(val userId: UserId, val addressId: String)

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("SetDefaultAddressUc.execute userId=${command.userId.value} addressId=${command.addressId}")
        val addressId = addressIdOf(command.addressId) ?: return Result.failure(Failure.AddressNotFound)

        return transactionManager.inTransaction {
            val book = addressBookRepository.lockAndFind(command.userId)
            book.makeDefault(addressId, clock.instant()).unwrapOr { return@inTransaction Result.failure(Failure.AddressNotFound) }
            addressBookRepository.save(book)
            Result.success(Unit)
        }
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        /** Not in this customer's book: someone else's, or nobody's (404 either way). */
        data object AddressNotFound : Failure("ADDRESS_NOT_FOUND", FailureKind.NOT_FOUND)
    }
}
