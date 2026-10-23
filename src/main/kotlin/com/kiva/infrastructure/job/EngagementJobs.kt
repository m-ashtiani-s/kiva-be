package com.kiva.infrastructure.job

import com.kiva.application.port.SmsKind
import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.contact.usecase.command.DeleteOldContactDataUc
import com.kiva.application.usecase.stockalert.usecase.command.DeleteOldStockAlertsUc
import com.kiva.application.usecase.stockalert.usecase.command.NotifyStockAlertUc
import com.kiva.application.usecase.stockalert.usecase.query.FindDueStockAlertsUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.stockalert.model.policy.StockAlertPolicy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * The jobs of the customer's engagement (roadmap section 15.5), like PurchaseJobs: each run finds candidates, then a
 * command in a transaction of its own decides each; one that breaks is logged and the others go on.
 *
 * | job | when |
 * |---|---|
 * | «موجود شد»: telling the customers whose bag is back, by SMS | every 5 minutes, not 22:00 to 08:00 Tehran |
 * | deleting subscriptions ended more than 90 days ago | daily, 04:45 Tehran |
 * | deleting old contact data: closed messages after a year, unsubscribed emails after 90 days (phase 13) | daily, 05:00 Tehran |
 */
class EngagementJobs(
    private val findDueStockAlerts: FindDueStockAlertsUc,
    private val notifyStockAlert: NotifyStockAlertUc,
    private val deleteOldStockAlerts: DeleteOldStockAlertsUc,
    private val deleteOldContactData: DeleteOldContactDataUc,
    private val smsSender: SmsSender,
    private val clock: Clock,
) {
    private val logger = getLogger()

    /** When the «no pattern» warning was last written: at most once an hour (decision 11). */
    private var warnedOn: Instant? = null

    fun all(): List<ScheduledJob> = listOf(
        ScheduledJob("notify-back-in-stock", Schedule.Every(Duration.ofMinutes(5))) { notifyBackInStock() },
        ScheduledJob("delete-old-stock-alerts", Schedule.DailyAt(LocalTime.of(4, 45), ShopCalendar.ZONE)) {
            deleteOldStockAlerts.execute(DeleteOldStockAlertsUc.Cmd)
        },
        ScheduledJob("delete-old-contact-data", Schedule.DailyAt(LocalTime.of(5, 0), ShopCalendar.ZONE)) {
            deleteOldContactData.execute(DeleteOldContactDataUc.Cmd)
        },
    )

    /**
     * One run of «موجود شد». Without an approved pattern it doesn't run at all (decision 11), so no subscription is used
     * up in silence; in the quiet hours it waits for the morning (decision 12).
     */
    suspend fun notifyBackInStock() {
        val now = clock.instant()
        if (!smsSender.canSend(SmsKind.BACK_IN_STOCK)) {
            if (warnedOn == null || Duration.between(warnedOn, now) >= Duration.ofHours(1)) {
                warnedOn = now
                logger.warn("EngagementJobs: no SMS pattern for «موجود شد» (KIVA_FARAZ_BACK_IN_STOCK_PATTERN); nobody is told, the subscriptions wait")
            }
            return
        }
        if (StockAlertPolicy.isQuietHour(now)) return
        val due = findDueStockAlerts.execute(FindDueStockAlertsUc.Qry()).unwrapOr { it }
        if (due.isNotEmpty()) logger.info("EngagementJobs: ${due.size} stock alerts are due")
        due.forEach { alertId ->
            try {
                notifyStockAlert.execute(NotifyStockAlertUc.Cmd(alertId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("EngagementJobs: stock alert ${alertId.value} failed; the next run tries again", e)
            }
        }
    }
}
