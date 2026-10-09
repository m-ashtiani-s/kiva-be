package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure

/**
 * A messenger the shop sends the photo of the packed bag through, before shipping («عکس قبل از ارسال»).
 * Values from the contract's PhotoMessengerChannel.
 */
enum class MessengerChannel {
    /** روبیکا */
    RUBIKA,

    /** تلگرام */
    TELEGRAM,

    /** بله */
    BALE,
    ;

    companion object {
        /** The value as the contract sends it (`TELEGRAM`), exactly; anything else is MESSENGER_CHANNEL_INVALID. */
        fun makeNew(raw: String): Result<MessengerChannel, Failure> =
            entries.firstOrNull { it.name == raw }?.let { Result.success(it) } ?: Result.failure(Failure.Invalid)
    }

    // No field here: the caller names it (`defaultMessenger` in the profile, the messenger chosen at checkout).
    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("MESSENGER_CHANNEL_INVALID")
    }
}
