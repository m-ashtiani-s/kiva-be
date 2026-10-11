package com.kiva.application.usecase.account.usecase.command

import com.kiva.application.dto.UserDto
import com.kiva.common.logging.getLogger
import com.kiva.common.patch.Patch
import com.kiva.common.patch.mapResult
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.model.ProfilePatch
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The logged-in user edits their own profile (`PATCH /me`): only the fields sent change, a sent null clears an
 * optional one. Every problem comes back at once as one VALIDATION_ERROR (User.updateProfile).
 *
 * The user is always the token's. The phone and the roles can't change here: they aren't in the command at all.
 *
 * Two edits at the same moment: the second one's save finds the row changed (optimistic locking) and stops with
 * ConcurrentChangeException, which the HTTP layer answers with 409 CHANGED_BY_SOMEONE_ELSE. Neither edit is lost
 * without anyone knowing: the second user reads the profile again and decides.
 */
class UpdateMyProfileUc(
    private val transactionManager: TransactionManager,
    private val userRepository: UserRepository,
    private val clock: Clock,
) : CommandUseCase<UpdateMyProfileUc.Cmd, Unit, UpdateMyProfileUc.Failure> {

    private val logger = getLogger()

    /**
     * The contract's UpdateProfileRequest as it arrived, plus whose profile it is.
     *
     * @property userId from the access token, never from the request.
     * @property birthDate as sent (`1996-09-10`).
     */
    data class Cmd(
        val userId: UserId,
        val firstName: Patch<String?> = Patch.Unchanged,
        val lastName: Patch<String?> = Patch.Unchanged,
        val email: Patch<String?> = Patch.Unchanged,
        val birthDate: Patch<String?> = Patch.Unchanged,
        val gender: Patch<String> = Patch.Unchanged,
        val defaultMessenger: Patch<String?> = Patch.Unchanged,
        val defaultMessengerPhone: Patch<String?> = Patch.Unchanged,
        val marketingSmsOptIn: Patch<Boolean> = Patch.Unchanged,
    ) {
        /** Only whose profile and which fields were sent: the values are personal data. */
        override fun toString(): String {
            val fields = mapOf(
                "firstName" to firstName, "lastName" to lastName, "email" to email, "birthDate" to birthDate, "gender" to gender,
                "defaultMessenger" to defaultMessenger, "defaultMessengerPhone" to defaultMessengerPhone, "marketingSmsOptIn" to marketingSmsOptIn,
            )
            return "Cmd(user=${userId.value}, sent=${fields.filterValues { it is Patch.Set }.keys})"
        }

        fun profilePatchOf(birthDate: Patch<LocalDate?>) = ProfilePatch(
            firstName, lastName, email, birthDate, gender, defaultMessenger, defaultMessengerPhone, marketingSmsOptIn,
        )
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("UpdateMyProfileUc.execute $command")

        // Read as a date first: a date picker sends ISO dates, so a broken one is a client bug, answered on its own.
        val birthDate = command.birthDate.mapResult(::dateOf)
            .unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        val patch = command.profilePatchOf(birthDate)
        val today = clock.instant().atZone(SHOP_ZONE).toLocalDate()

        return transactionManager.inTransaction {
            val user = userRepository.findById(command.userId) ?: return@inTransaction Result.failure(Failure.AccountGone)
            val updated = user.updateProfile(patch, today)
                .mapOnFailure { Failure.InvalidInput(it.causes) }
                .unwrapOr { return@inTransaction Result.failure(it) }
            // The same profile again (or `{}`) saves nothing: no new version, so no one else's edit is refused for it.
            if (UserDto.from(updated) != UserDto.from(user)) userRepository.save(updated)
            Result.success(Unit)
        }
    }

    private fun dateOf(raw: String?): Result<LocalDate?, ResultFailure> {
        if (raw == null) return Result.success(null)
        return try {
            Result.success(LocalDate.parse(raw.trim()))
        } catch (e: DateTimeParseException) {
            Result.failure(BirthDateUnreadable)
        }
    }

    /** `birthDate` is not a date like `1996-09-10`. */
    data object BirthDateUnreadable : ResultFailure("BIRTH_DATE_INVALID", FailureKind.INVALID, field = "birthDate")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList()) :
        ResultFailure(code, kind, causes = causes) {

        /** Every problem of the form, each under its field. */
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** A token of an account that no longer exists: log in again. */
        data object AccountGone : Failure("UNAUTHORIZED", FailureKind.UNAUTHORIZED)
    }

    private companion object {
        /** The shop's time zone: "today" for a birth date is Tehran's today. */
        val SHOP_ZONE: ZoneId = ZoneId.of("Asia/Tehran")
    }
}
