package com.kiva.application.usecase.returnrequest

import com.kiva.application.port.SmsSender
import com.kiva.common.logging.getLogger
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnCode
import kotlin.coroutines.cancellation.CancellationException

/**
 * The SMS a return's customer gets as staff work on it (roadmap section 12.9), one place for every use case that sends
 * one (Rule 15). Only the return's code, never staff's reason (no free text in an SMS, roadmap section 1, row 14): the
 * reason is on the return's page. Always after the commit, never inside a transaction; a failure is only a WARN: the
 * return moved all the same.
 *
 * The phone is the buyer's account's, read by the caller inside its transaction; none (an account removed) sends nothing.
 */
class ReturnNotices(private val smsSender: SmsSender) {

    private val logger = getLogger()

    /** «مرجوعی تأیید شد». */
    suspend fun approved(to: IranianMobileNumber?, code: ReturnCode) = send("«مرجوعی تأیید شد»", to, code) { smsSender.sendReturnApproved(it, code) }

    /** «مرجوعی رد شد». */
    suspend fun rejected(to: IranianMobileNumber?, code: ReturnCode) = send("«مرجوعی رد شد»", to, code) { smsSender.sendReturnRejected(it, code) }

    /** «جمع‌آوری مرجوعی», with the carrier's name. */
    suspend fun pickup(to: IranianMobileNumber?, code: ReturnCode, carrierName: String) =
        send("«جمع‌آوری مرجوعی»", to, code) { smsSender.sendReturnPickup(it, code, carrierName) }

    /** «مرجوعی بسته شد». */
    suspend fun closed(to: IranianMobileNumber?, code: ReturnCode) = send("«مرجوعی بسته شد»", to, code) { smsSender.sendReturnClosed(it, code) }

    private suspend fun send(what: String, to: IranianMobileNumber?, code: ReturnCode, sending: suspend (IranianMobileNumber) -> Unit) {
        if (to == null) {
            logger.warn("ReturnNotices: $what of return ${code.value} not sent, its account is gone")
            return
        }
        try {
            sending(to)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("ReturnNotices: $what of return ${code.value} to ${to.masked()} failed; the return is changed all the same", e)
        }
    }
}
