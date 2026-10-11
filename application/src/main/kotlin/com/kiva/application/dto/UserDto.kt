package com.kiva.application.dto

import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant
import java.time.LocalDate

/**
 * A user as their account page and every response with a `user` show it (the contract's User), in plain values.
 * The display name and initial are not here: they are only how the response words it.
 *
 * @property gender, defaultMessenger, roles the contract's names (`FEMALE`, `TELEGRAM`, `CUSTOMER`).
 */
data class UserDto(
    val id: Long,
    val phone: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val birthDate: LocalDate?,
    val gender: String,
    val defaultMessenger: String?,
    val defaultMessengerPhone: String?,
    val marketingSmsOptIn: Boolean,
    val roles: List<String>,
    val createdOn: Instant,
) {
    companion object {
        fun from(user: User) = UserDto(
            id = user.id.value,
            phone = user.phone.value,
            firstName = user.firstName?.value,
            lastName = user.lastName?.value,
            email = user.email?.value,
            birthDate = user.birthDate?.value,
            gender = user.gender.name,
            defaultMessenger = user.defaultMessenger?.name,
            defaultMessengerPhone = user.defaultMessengerPhone?.value,
            marketingSmsOptIn = user.marketingSmsOptIn,
            roles = user.roles.values.map { it.name },
            createdOn = user.createdOn,
        )
    }
}
