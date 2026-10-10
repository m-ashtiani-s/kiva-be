package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.IssuedSession
import com.kiva.application.dto.UserDto
import com.kiva.application.usecase.account.usecase.command.RequestPhoneChangeUc
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import kotlinx.serialization.Serializable

/** The contract's PhoneChangeOtpResponse: where the two codes went, with the same timers as a login code. */
@Serializable
data class PhoneChangeOtpHttpResponse(
    val currentPhone: String,
    val newPhone: String,
    val codeLength: Int,
    val expiresInSeconds: Long,
    val resendAvailableInSeconds: Long,
) {
    companion object {
        fun of(sent: RequestPhoneChangeUc.CodesSent) = PhoneChangeOtpHttpResponse(
            currentPhone = sent.currentPhone.value,
            newPhone = sent.newPhone.value,
            codeLength = OtpPolicy.CODE_LENGTH,
            expiresInSeconds = OtpPolicy.LIFETIME.seconds,
            resendAvailableInSeconds = OtpPolicy.RESEND_AFTER.seconds,
        )
    }
}

/**
 * The contract's PhoneChangeResponse: AuthTokens of the new session plus the user. The refresh token is in the cookie.
 *
 * @property expiresIn the access token's lifetime, in seconds; [refreshExpiresIn] the cookie's.
 */
@Serializable
data class PhoneChangeHttpResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val refreshExpiresIn: Long,
    val user: UserHttpResponse,
) {
    // The access token is a secret, if this is ever printed.
    override fun toString(): String = "PhoneChangeHttpResponse(user=${user.id}, accessToken=<hidden>)"

    companion object {
        fun of(session: IssuedSession, user: UserDto) = PhoneChangeHttpResponse(
            accessToken = session.accessToken,
            tokenType = "Bearer",
            expiresIn = session.accessTokenExpiresIn.seconds,
            refreshExpiresIn = session.refreshTokenMaxAge.seconds,
            user = UserHttpResponse.from(user),
        )
    }
}
