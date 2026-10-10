package com.kiva.infrastructure.auth.sms

import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose

/**
 * Development only (`kiva.sms.provider = log`): writes the code to the log instead of sending it, so you can log in
 * without an SMS provider. The one place a code is ever written to a log, which is why it says so loudly on startup.
 */
class LogSmsSender : SmsSender {

    private val logger = getLogger()

    init {
        logger.warn("SMS provider is 'log': login codes are written to this log instead of being sent. Development only.")
    }

    override suspend fun sendCode(to: IranianMobileNumber, code: OtpCode, purpose: OtpPurpose) {
        logger.warn("SMS to ${to.masked()} ($purpose): code ${code.value}")
    }

    override suspend fun sendPhoneChangedNotice(oldPhone: IranianMobileNumber, newPhoneEnding: String) {
        logger.warn("SMS to ${oldPhone.masked()}: your Kiva account moved to the number ending in $newPhoneEnding")
    }
}
