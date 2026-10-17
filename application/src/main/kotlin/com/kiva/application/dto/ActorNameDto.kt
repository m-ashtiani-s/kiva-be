package com.kiva.application.dto

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User

/**
 * Who did something in the panel, as the panel shows it (a product's `createdBy`, a stock change's): the person's full
 * name, otherwise their phone masked, or the system. Read from the account as it is now; the HTTP layer writes it out
 * («سیستم» for the system).
 */
sealed interface ActorNameDto {
    data object System : ActorNameDto

    /** @property fullName first and last name, or null when the account has neither. */
    data class Person(val fullName: String?, val maskedPhone: String) : ActorNameDto {
        override fun toString() = "Person(***)"
    }

    /** An account removed since: a user id nobody has any more. */
    data object Unknown : ActorNameDto

    companion object {
        /** @param user the account of [id], or null when there is none (any more). */
        fun of(id: UserId, user: User?): ActorNameDto = when {
            id == UserId.SYSTEM -> System
            user == null -> Unknown
            else -> Person(
                fullName = listOfNotNull(user.firstName?.value, user.lastName?.value).joinToString(" ").ifEmpty { null },
                maskedPhone = user.phone.masked(),
            )
        }
    }
}
