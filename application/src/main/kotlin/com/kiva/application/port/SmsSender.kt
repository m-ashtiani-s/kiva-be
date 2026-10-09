package com.kiva.application.port

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose

/**
 * Sends SMS through the shop's SMS provider. The domain doesn't know which provider; development writes to the log.
 */
interface SmsSender {

    /**
     * Sends [code] to [to]. [purpose] picks the provider's approved template (Iranian providers send codes only
     * through templates approved in advance).
     *
     * Throws when the provider refuses or can't be reached. It is called after the code is committed, never inside a
     * transaction: a slow provider must not hold a database connection.
     */
    suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose)
}
