package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import kotlinx.serialization.Serializable

/**
 * The contract's SendOtpResponse. The same for every valid phone, with an account or without: only the phone itself
 * differs, so the answer tells nothing about who shops at Kiva.
 *
 * @property phone in its stored form, for «کد به ۰۹۱۲... ارسال شد».
 * @property resendAvailableInSeconds the round resend timer.
 */
@Serializable
data class SendOtpHttpResponse(
    val phone: String,
    val codeLength: Int,
    val expiresInSeconds: Long,
    val resendAvailableInSeconds: Long,
) {
    companion object {
        fun of(phone: IranianMobileNumber) = SendOtpHttpResponse(
            phone = phone.value,
            codeLength = OtpPolicy.CODE_LENGTH,
            expiresInSeconds = OtpPolicy.LIFETIME.seconds,
            resendAvailableInSeconds = OtpPolicy.RESEND_AFTER.seconds,
        )
    }
}
