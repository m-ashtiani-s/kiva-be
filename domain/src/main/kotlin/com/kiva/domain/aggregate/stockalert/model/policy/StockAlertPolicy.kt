package com.kiva.domain.aggregate.stockalert.model.policy

import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import java.time.Duration
import java.time.Instant
import java.time.LocalTime

/** The numbers of «موجود شد خبرم کن» (roadmap section 15.2, decisions 5, 8, 12 and 13). */
object StockAlertPolicy {

    /** At most this many ACTIVE subscriptions per customer (STOCK_ALERT_LIMIT_REACHED). */
    const val MAX_ACTIVE = 30

    /** At most this many made in [RATE_WINDOW] (429 RATE_LIMITED). */
    const val MAX_PER_HOUR = 10
    val RATE_WINDOW: Duration = Duration.ofHours(1)

    /** An ended subscription is kept this long, then the nightly job takes it; «اشتراک‌های من» shows NOTIFIED ones this far back. */
    const val KEEP_DAYS = 90L

    /** «اشتراک‌های من» shows at most this many. */
    const val MINE_LIMIT = 100

    /** No SMS from 22:00 to 08:00 in Tehran: they wait for the morning. */
    val QUIET_FROM: LocalTime = LocalTime.of(22, 0)
    val QUIET_TO: LocalTime = LocalTime.of(8, 0)

    /** At most this many told in one run of the job, the oldest first. */
    const val BATCH = 200

    fun isQuietHour(now: Instant): Boolean {
        val time = now.atZone(ShopCalendar.ZONE).toLocalTime()
        return !time.isBefore(QUIET_FROM) || time.isBefore(QUIET_TO)
    }
}
