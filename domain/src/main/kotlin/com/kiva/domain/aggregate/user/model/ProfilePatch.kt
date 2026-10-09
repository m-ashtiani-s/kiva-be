package com.kiva.domain.aggregate.user.model

import com.kiva.common.patch.Patch
import java.time.LocalDate

/**
 * The profile form as it arrived (`PATCH /me`): for each field, either not sent or a new value. Values are raw;
 * [com.kiva.domain.aggregate.user.entity.User.updateProfile] checks them all at once.
 *
 * For a field that can be empty, a new value of null clears it. An empty string does not clear: it is refused, so a
 * form that sends "" by mistake never wipes a name.
 *
 * Phone and roles are not here. They change only through their own flows (phone change with two codes, the admin's
 * roles page), so a `phone` or `roles` in the request body can't change anything.
 *
 * @property birthDate already a date: the HTTP layer refuses text that isn't one.
 */
data class ProfilePatch(
    val firstName: Patch<String?> = Patch.Unchanged,
    val lastName: Patch<String?> = Patch.Unchanged,
    val email: Patch<String?> = Patch.Unchanged,
    val birthDate: Patch<LocalDate?> = Patch.Unchanged,
    val gender: Patch<String> = Patch.Unchanged,
    val defaultMessenger: Patch<String?> = Patch.Unchanged,
    val defaultMessengerPhone: Patch<String?> = Patch.Unchanged,
    val marketingSmsOptIn: Patch<Boolean> = Patch.Unchanged,
) {
    /** Only which fields were sent: the values are personal data (names, email, a phone) and stay out of logs. */
    override fun toString(): String {
        val sent = listOf(
            "firstName" to firstName,
            "lastName" to lastName,
            "email" to email,
            "birthDate" to birthDate,
            "gender" to gender,
            "defaultMessenger" to defaultMessenger,
            "defaultMessengerPhone" to defaultMessengerPhone,
            "marketingSmsOptIn" to marketingSmsOptIn,
        ).filter { it.second is Patch.Set }.map { it.first }
        return "ProfilePatch(sent=$sent)"
    }
}
