package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import java.time.LocalDate

/**
 * The customer's birthday (for a birthday gift). A Gregorian date; the client shows it in the Persian calendar.
 */
@JvmInline
value class BirthDate private constructor(val value: LocalDate) {

    // Second wall, for every way in: [makeNew] already refuses bad input with a Failure, so this only ever fails
    // when stored data is wrong. That is a bug: stop loudly. "Not in the future" needs today, so only makeNew checks it.
    init {
        require(value >= EARLIEST) { "Invalid BirthDate $value" }
    }

    companion object {
        val EARLIEST: LocalDate = LocalDate.of(1900, 1, 1)

        /**
         * From [EARLIEST] up to [today], both included.
         *
         * @param today the date in Tehran (the shop's time zone), from the use case's clock. With the server's own
         *   zone, someone born today could be refused for a few hours after midnight in Tehran.
         */
        fun makeNew(raw: LocalDate, today: LocalDate): Result<BirthDate, Failure> =
            if (raw < EARLIEST || raw > today) Result.failure(Failure.OutOfRange) else Result.success(BirthDate(raw))

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: LocalDate): BirthDate = BirthDate(value)
    }

    // Always the `birthDate` field of the contract.
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID, field = "birthDate") {
        data object OutOfRange : Failure("BIRTH_DATE_INVALID")
    }
}
