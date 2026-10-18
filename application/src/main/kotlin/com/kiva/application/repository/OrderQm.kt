package com.kiva.application.repository

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.policy.OrderActionFacts
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/**
 * One order as its read model has it: what the customer's lists and pages, the panel and the jobs read, instead of its
 * events. The same values the order's events hold, plus its [timeline]. Its payments and refunds are not here (rows of
 * their own).
 *
 * Personal data inside: [toString] shows only the order's id, code and status.
 *
 * @property timeline one entry per event the customer sees, oldest first (`OrderDetail.timeline`).
 * @property photoDraft the operator's photos not sent yet: the panel's only.
 * @property photosSent the last batch sent to the customer.
 * @property feedback the customer's last answer to the photos sent.
 * @property refunded what went back to the customer: the completed refunds.
 */
data class OrderQm(
    val orderId: OrderId,
    val code: OrderCode,
    val userId: UserId,
    val status: OrderStatus,
    val placedOn: Instant,
    val paidOn: Instant?,
    val holdUntil: Instant,
    val shippingMethod: ShippingMethodCode,
    val reserveRequested: Boolean,
    val reservationGroupId: ReservationGroupId?,
    val lines: List<OrderLine>,
    val address: AddressSnapshot,
    val messenger: PreShipmentMessenger,
    val totals: OrderTotals,
    val timeline: List<TimelineEntry>,
    val customerNote: CustomerNote?,
    val gift: GiftNote?,
    val photoStatus: PhotoStatus,
    val photoEverSent: Boolean,
    val photoDraft: List<OrderPhoto>,
    val photosSent: List<OrderPhoto>,
    val photoBatch: Int,
    val photosSentOn: Instant?,
    val feedbackDeadline: Instant?,
    val feedback: Feedback?,
    val shipment: Shipment?,
    val deliveredOn: Instant?,
    val returnableUntil: Instant?,
    val cancellation: Cancellation?,
    val refunded: Money,
) {
    /**
     * What happened to the order and when: [kind], the [status] it was in after it, and for a failed payment attempt its
     * [failureReason]. The label is the HTTP layer's.
     *
     * @property note a cancellation's or a delivery's note, shown to the customer.
     * @property amount the money given back, for [OrderTimelineKind.REFUNDED].
     */
    data class TimelineEntry(
        val kind: OrderTimelineKind,
        val status: OrderStatus,
        val at: Instant,
        val failureReason: PaymentFailureReason?,
        val note: String? = null,
        val amount: Money? = null,
    ) {
        override fun toString(): String = "TimelineEntry(kind=$kind, status=$status, at=$at)"
    }

    /** The customer's answer to the photos: only APPROVE in this phase. */
    data class Feedback(val decision: FeedbackDecision, val at: Instant)

    /** The parcel it left in; its carrier is the order's [shippingMethod]. */
    data class Shipment(val trackingCode: TrackingCode, val shippedOn: Instant, val showInDailyList: Boolean)

    /** How it was cancelled; [note] is personal. */
    data class Cancellation(val by: CancelledBy, val reason: CancelReason?, val note: String?, val cancelledOn: Instant) {
        override fun toString(): String = "Cancellation(by=$by, reason=$reason, cancelledOn=$cancelledOn)"
    }

    /** What its buttons depend on at [now], from what the read model has. */
    fun actionFactsAt(now: Instant): OrderActionFacts = OrderActionFacts(status, holdUntil, photoEverSent, photoStatus, now)

    override fun toString(): String = "OrderQm(orderId=${orderId.value}, code=${code.value}, status=$status)"
}

/** What a timeline entry says happened; each has its own Persian label (OrderTexts). */
enum class OrderTimelineKind {
    PLACED,
    PAYMENT_FAILED,

    /** Paid; held with «رزرو ۴ روزه» when its status is RESERVED. */
    PAID,
    EXPIRED,
    RELEASED_FROM_RESERVATION,
    PHOTOS_SENT,

    /** The customer said «همونه!». */
    PHOTOS_APPROVED,

    /** Nobody answered the photos in time: approved by the system. */
    PHOTOS_AUTO_APPROVED,
    SHIPPED,
    SHIPMENT_CORRECTED,
    DELIVERED,
    CANCELLED,
    REFUNDED,
}

/** The customer's answer to the photos sent (`PreShipmentFeedbackRequest.decision`). */
enum class FeedbackDecision {
    APPROVE,
    REQUEST_CHANGE,
}
