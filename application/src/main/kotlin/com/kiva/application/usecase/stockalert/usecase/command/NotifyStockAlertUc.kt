package com.kiva.application.usecase.stockalert.usecase.command

import com.kiva.application.port.SmsSender
import com.kiva.application.repository.ProductQmRepository
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import com.kiva.domain.aggregate.stockalert.repository.StockAlertRepository
import com.kiva.domain.aggregate.user.repository.UserRepository
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tells one customer their bag is back (the job `notify-back-in-stock`; roadmap section 15.6, decisions 9, 10 and 14):
 * under the subscription's row lock it is checked again (still ACTIVE, the product still in the shop, its color, or for
 * «any color» one active color, still left), marked NOTIFIED with the color that came back, committed, and only then
 * the SMS goes, to the account's phone of this moment. A failed SMS is only a warning: the subscription stays NOTIFIED,
 * because a second SMS is worse than none. Its phone is never logged.
 */
class NotifyStockAlertUc(
    private val transactionManager: TransactionManager,
    private val stockAlertRepository: StockAlertRepository,
    private val productQmRepository: ProductQmRepository,
    private val userRepository: UserRepository,
    private val smsSender: SmsSender,
    private val siteUrl: String,
    private val clock: Clock,
) : CommandUseCase<NotifyStockAlertUc.Cmd, NotifyStockAlertUc.Outcome, Nothing> {

    private val logger = getLogger()

    data class Cmd(val alertId: StockAlertId)

    enum class Outcome { SENT, NOT_DUE }

    /** What the SMS says and where it goes (the account's phone now; null when the account is gone), read inside the transaction. */
    private class Message(val to: IranianMobileNumber?, val productName: String, val link: String)

    override suspend fun execute(command: Cmd): Result<Outcome, Nothing> {
        val message = transactionManager.inTransaction {
            val alert = stockAlertRepository.lock(command.alertId)?.takeIf { it.status == StockAlertStatus.ACTIVE }
                ?: return@inTransaction Result.success(null)
            val card = productQmRepository.findCards(listOf(alert.productId)).singleOrNull() ?: return@inTransaction Result.success(null)
            // The color asked for, or for «any color» the first one left in display order.
            val back = card.colors.firstOrNull { it.available > 0 && (alert.variantId == null || it.variantId == alert.variantId) }
                ?: return@inTransaction Result.success(null)
            alert.markNotified(back.variantId, clock.instant())
            stockAlertRepository.save(alert)
            val phone = userRepository.findById(alert.userId)?.phone
            Result.success(Message(phone, card.name.value, "${siteUrl.trimEnd('/')}/product/${card.slug.value}?color=${back.colorKey.value}"))
        }.getOrNull()

        if (message == null) {
            logger.info("NotifyStockAlertUc: alert ${command.alertId.value} is no longer due")
            return Result.success(Outcome.NOT_DUE)
        }
        logger.info("NotifyStockAlertUc: alert ${command.alertId.value} NOTIFIED, sending its SMS")
        val phone = message.to
        if (phone == null) {
            logger.warn("NotifyStockAlertUc: alert ${command.alertId.value} has no account phone any more; nothing sent")
            return Result.success(Outcome.SENT)
        }
        try {
            smsSender.sendBackInStock(phone, shortName(message.productName), message.link)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("NotifyStockAlertUc: the SMS of alert ${command.alertId.value} failed; it stays NOTIFIED", e)
        }
        return Result.success(Outcome.SENT)
    }

    companion object {
        /** A pattern's variable is short: a longer name is cut, with «…». */
        const val MAX_NAME = 30

        fun shortName(name: String): String = if (name.length <= MAX_NAME) name else name.take(MAX_NAME - 1) + "…"
    }
}
