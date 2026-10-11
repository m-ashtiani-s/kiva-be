package com.kiva.infrastructure.httpserver.controller.httprest.model.request

import com.kiva.common.patch.Patch
import com.kiva.infrastructure.common.http.PatchSerializer
import kotlinx.serialization.Serializable

/**
 * The contract's UpdateProfileRequest, as it arrives: each field not sent, or sent with a value (null clears an optional
 * one). Raw: the use case checks it. `phone` and `roles` are not here, so sending them changes nothing (unknown fields
 * are ignored).
 *
 * @property birthDate `1996-09-10`; the use case reads it as a date.
 */
@Serializable
data class UpdateProfileHttpRequest(
    @Serializable(with = PatchSerializer::class) val firstName: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val lastName: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val email: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val birthDate: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val gender: Patch<String> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val defaultMessenger: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val defaultMessengerPhone: Patch<String?> = Patch.Unchanged,
    @Serializable(with = PatchSerializer::class) val marketingSmsOptIn: Patch<Boolean> = Patch.Unchanged,
)
