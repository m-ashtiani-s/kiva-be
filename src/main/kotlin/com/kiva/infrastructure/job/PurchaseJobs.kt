package com.kiva.infrastructure.job

import com.kiva.application.usecase.cart.usecase.command.DeleteStaleGuestCartsUc
import com.kiva.application.usecase.idempotency.usecase.command.DeleteOldIdempotencyKeysUc
import com.kiva.application.usecase.order.usecase.command.ExpireUnpaidOrderUc
import com.kiva.application.usecase.order.usecase.query.FindExpirableOrdersUc
import com.kiva.application.usecase.payment.usecase.command.ReconcilePaymentUc
import com.kiva.application.usecase.payment.usecase.query.FindStalePaymentsUc
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import java.time.Duration
import java.time.LocalTime
import kotlin.coroutines.cancellation.CancellationException

/**
 * The jobs of the purchase (roadmap section 15). Each run of a job is a query that finds candidates and, for each, a
 * command in a transaction of its own that decides with the aggregate; one candidate that breaks is logged and the
 * others go on. Every run is right however often it runs, on however many instances of the app.
 *
 * | job | when |
 * |---|---|
 * | expiring unpaid orders | every minute |
 * | reconciling payments | every 2 minutes |
 * | removing guests' carts nobody came back to | daily, 04:00 Tehran |
 * | forgetting old Idempotency-Keys | hourly |
 */
class PurchaseJobs(
    private val findExpirableOrders: FindExpirableOrdersUc,
    private val expireUnpaidOrder: ExpireUnpaidOrderUc,
    private val findStalePayments: FindStalePaymentsUc,
    private val reconcilePayment: ReconcilePaymentUc,
    private val deleteStaleGuestCarts: DeleteStaleGuestCartsUc,
    private val deleteOldIdempotencyKeys: DeleteOldIdempotencyKeysUc,
) {
    private val logger = getLogger()

    fun all(): List<ScheduledJob> = listOf(
        ScheduledJob("expire-unpaid-orders", Schedule.Every(Duration.ofMinutes(1))) { expireUnpaidOrders() },
        ScheduledJob("reconcile-payments", Schedule.Every(Duration.ofMinutes(2))) { reconcilePayments() },
        ScheduledJob("delete-stale-guest-carts", Schedule.DailyAt(LocalTime.of(4, 0), ShopCalendar.ZONE)) {
            deleteStaleGuestCarts.execute(DeleteStaleGuestCartsUc.Cmd)
        },
        ScheduledJob("delete-old-idempotency-keys", Schedule.Every(Duration.ofHours(1))) {
            deleteOldIdempotencyKeys.execute(DeleteOldIdempotencyKeysUc.Cmd)
        },
    )

    /** One run of the expiry: every unpaid order past its time to pay, each decided by itself. */
    suspend fun expireUnpaidOrders() {
        val candidates = findExpirableOrders.execute(FindExpirableOrdersUc.Qry()).unwrapOr { it }
        candidates.forEach { orderId -> each("order ${orderId.value}") { expireUnpaidOrder.execute(ExpireUnpaidOrderUc.Cmd(orderId)) } }
    }

    /** One run of the reconciliation: every attempt the shopper's callback didn't settle, each settled by itself. */
    suspend fun reconcilePayments() {
        val candidates = findStalePayments.execute(FindStalePaymentsUc.Qry()).unwrapOr { it }
        candidates.forEach { paymentId -> each("payment ${paymentId.value}") { reconcilePayment.execute(ReconcilePaymentUc.Cmd(paymentId)) } }
    }

    private suspend fun each(what: String, work: suspend () -> Unit) {
        try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("PurchaseJobs: $what failed; the next run tries again", e)
        }
    }
}
