package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.UserDto
import kotlinx.serialization.Serializable

/**
 * The contract's User. [fullName], [displayName] and [avatarInitial] are not stored: they are worded here, from the
 * names, so the greeting reads «سلام سارا», or «سلام دوست عزیز» before the customer says what to call them.
 *
 * @property birthDate Gregorian (`1996-09-10`); the client shows it in the Persian calendar.
 * @property createdAt when the account was made, ISO-8601 in UTC.
 */
@Serializable
data class UserHttpResponse(
    val id: Long,
    val phone: String,
    val firstName: String?,
    val lastName: String?,
    val fullName: String?,
    val displayName: String,
    val avatarInitial: String,
    val email: String?,
    val birthDate: String?,
    val gender: String,
    val defaultMessenger: String?,
    val defaultMessengerPhone: String?,
    val marketingSmsOptIn: Boolean,
    val roles: List<String>,
    val createdAt: String,
) {
    companion object {
        /** What the shop calls someone who hasn't given a name yet. */
        const val NAMELESS = "دوست عزیز"

        fun from(user: UserDto): UserHttpResponse {
            val displayName = user.firstName ?: NAMELESS
            return UserHttpResponse(
                id = user.id,
                phone = user.phone,
                firstName = user.firstName,
                lastName = user.lastName,
                fullName = listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifEmpty { null },
                displayName = displayName,
                // The first character as a person sees it, even outside the basic plane.
                avatarInitial = displayName.substring(0, displayName.offsetByCodePoints(0, 1)),
                email = user.email,
                birthDate = user.birthDate?.toString(),
                gender = user.gender,
                defaultMessenger = user.defaultMessenger,
                defaultMessengerPhone = user.defaultMessengerPhone,
                marketingSmsOptIn = user.marketingSmsOptIn,
                roles = user.roles,
                createdAt = user.createdOn.toString(),
            )
        }
    }
}
