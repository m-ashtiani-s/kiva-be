package com.kiva.domain.aggregate.order.entity

import com.kiva.common.event.EventSourceEntity
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.common.valueobject.sum
import com.kiva.domain.aggregate.order.entity.event.OrderBalancePaid
import com.kiva.domain.aggregate.order.entity.event.OrderCancelled
import com.kiva.domain.aggregate.order.entity.event.OrderChangeApplied
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRejected
import com.kiva.domain.aggregate.order.entity.event.OrderChangeRequested
import com.kiva.domain.aggregate.order.entity.event.OrderDelivered
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderLineRemoved
import com.kiva.domain.aggregate.order.entity.event.OrderLineReplaced
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPersonalDataErased
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosApproved
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosAttached
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderRefunded
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.entity.event.OrderReturnEnded
import com.kiva.domain.aggregate.order.entity.event.OrderReturnReceived
import com.kiva.domain.aggregate.order.entity.event.OrderReturnRequested
import com.kiva.domain.aggregate.order.entity.event.OrderShipmentCorrected
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.order.model.policy.ChangeRequestPolicy
import com.kiva.domain.aggregate.order.model.policy.OrderTransitionPolicy
import com.kiva.domain.aggregate.order.model.policy.PaymentPolicy
import com.kiva.domain.aggregate.order.model.policy.PreShipmentPolicy
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeRequest
import com.kiva.domain.aggregate.order.model.valueobject.ChangeTarget
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCancellationInfo
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderShipment
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.EXPIRED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PAYMENT_FAILED
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PENDING_PAYMENT
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PROCESSING
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.RESERVED
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotalsView
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.order.model.valueobject.PhotoStatus
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.returnrequest.model.policy.ReturnPolicy
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnItem
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Duration
import java.time.Instant
import kotlin.properties.Delegates

/**
 * A customer's order: what they bought and at what price, where it goes, how it ships, and whether it is paid.
 *
 * Event-sourced: a long life with a timeline (`OrderDetail.timeline` is its events), and a financial record. Everything
 * is copied when it is placed ([OrderPlaced]), so a later price, product name or saved address never changes it.
 *
 * Rules it keeps by itself:
 * - it has at least one line, and all colors of a product together are at most that product's limit per order;
 * - an order with nothing to pay is paid the moment it is placed, so an unpaid order always has something to pay;
 * - it is paid only while unpaid, exactly its payable, once;
 * - a failed payment attempt is recorded once, and only while the order is unpaid;
 * - it expires only while unpaid and not before its [holdUntil]; expiring twice changes nothing;
 * - after it is paid (phase 2, roadmap section 7.3): photos are drafted and sent only while PROCESSING with photos
 *   WAITING, and an order of several lines has a photo of each; it ships only from PHOTO_SENT, with the carrier it was
 *   paid for, once its photos are approved or their deadline passed; it is delivered only when SHIPPED; the customer
 *   cancels it only while its status hasn't moved (OrderTransitionPolicy.customerMayCancel), staff until it ships, once;
 *   and the money given back never adds up to more than it was paid.
 * - a change request (phase 3, roadmap section 8.3): the customer asks one only while PHOTO_SENT with its photos SENT or
 *   APPROVED, so never two at once; a line is swapped for another color of the same product (COLOR) or of another
 *   (MODEL), keeping its number and quantity, within the new product's limit per order, or taken out (CANCEL_ITEM, never
 *   the only line); the money of a change is settled once, owed first, refunded only beyond what is owed
 *   (ChangeRequestPolicy.settle); what is owed is paid only exactly, once per attempt; it never ships while something is
 *   owed; and a cancelled order owes nothing.
 * - a return (phase 7, roadmap section 12.4): asked for only while DELIVERED, within [returnableUntil], never after a
 *   return of it was closed or refunded ([returnSettled]), so one at a time; of its returnable lines only, each once, never
 *   more than was bought; RETURNED only when the open return's parcel arrived, REFUNDED only after that.
 * - its personal data (phase 14, roadmap section 19.3) is erased once, with nothing else changed: the address down to its
 *   province and city, the messenger to its channel, no customer note, no gift message.
 *
 * What it can't know: its stock (the Inventory of each color holds it for the order, by its [code]), its payments and
 * refunds (rows of their own: RefundDomainService counts what is refundable), its reservation group's state, whether
 * a photo it is given exists (the use case checks the media), and the catalog: the color a change asks for and its price
 * today come from the use case ([ChangeTarget], a line made with OrderLine.replacedBy). A return is a row of its own
 * (ReturnRequest): the order only knows which one is open, and records the return's steps that change the order.
 */
class Order private constructor() : EventSourceEntity<OrderDomainEvent>() {

    // Set by apply(). Kotlin doesn't allow lateinit on value classes, so those use Delegates.notNull().
    var id: OrderId by Delegates.notNull()
        private set
    var code: OrderCode by Delegates.notNull()
        private set
    var userId: UserId by Delegates.notNull()
        private set
    var status: OrderStatus = PENDING_PAYMENT
        private set
    lateinit var address: AddressSnapshot
        private set
    lateinit var messenger: PreShipmentMessenger
        private set
    lateinit var shippingMethod: ShippingMethodCode
        private set
    lateinit var totals: OrderTotals
        private set

    /** «رزرو ۴ روزه» was asked for, for an order of its own (round 2). */
    var reserveRequested: Boolean = false
        private set

    /** The reservation group it ships with: one it joined when placed, or one it started or joined when paid. */
    var reservationGroupId: ReservationGroupId? = null
        private set
    lateinit var placedOn: Instant
        private set

    /** The end of the time to pay and of the stock hold (`PaymentInit.expiresAt`). Never moved, not even by a retry. */
    lateinit var holdUntil: Instant
        private set
    var paidOn: Instant? = null
        private set

    /** The attempt that paid it; null while unpaid, and for an order with nothing to pay. */
    var paidByPaymentId: PaymentPublicId? = null
        private set
    var customerNote: CustomerNote? = null
        private set
    var gift: GiftNote? = null
        private set

    private val lineList = mutableListOf<OrderLine>()
    private val failedAttempts = mutableSetOf<PaymentPublicId>()

    // ---- after the payment (phase 2) ----

    /** The operator's photos not sent yet; the customer doesn't see them. */
    var photoDraft: List<OrderPhoto> = emptyList()
        private set

    /** The last batch sent to the customer: what they see. */
    var photosSent: List<OrderPhoto> = emptyList()
        private set

    /** How many batches were sent: 0 before the first. */
    var photoBatch: Int = 0
        private set
    var photoStatus: PhotoStatus = PhotoStatus.WAITING
        private set

    /** A photo was sent once: from then on the customer no longer cancels it themselves, even back in PROCESSING. */
    var photoEverSent: Boolean = false
        private set

    /** When the last batch was sent. */
    var photosSentOn: Instant? = null
        private set

    /** Until when the customer may answer the last batch; then it counts as approved. */
    var feedbackDeadline: Instant? = null
        private set
    var shipment: OrderShipment? = null
        private set
    var deliveredOn: Instant? = null
        private set
    var returnableUntil: Instant? = null
        private set
    var cancellation: OrderCancellationInfo? = null
        private set

    /** What went back to the customer: the completed refunds. */
    var refunded: Money = Money.ZERO
        private set
    private val refundIds = mutableSetOf<RefundId>()

    // ---- a change request after the photos (phase 3) ----

    /** The customer's change request staff haven't resolved yet: there while CHANGE_REQUESTED, null otherwise. */
    var openChange: ChangeRequest? = null
        private set

    /** What the order still owes after a change to a dearer color; it doesn't ship until this is 0. */
    var balanceDue: Money = Money.ZERO
        private set

    /** What was paid for changes on top of the placed payable. */
    var balancePaid: Money = Money.ZERO
        private set

    /** What the changes gave back, as refunds of their own rows (the identity of OrderTotalsView). */
    var differenceRefunded: Money = Money.ZERO
        private set

    /** The discount code's share of the lines taken out (OrderTotalsView.codeDiscount). */
    var codeDiscountRemoved: Money = Money.ZERO
        private set
    private val balancePayments = mutableSetOf<PaymentPublicId>()

    // ---- a return after the delivery (phase 7) ----

    /** The return being dealt with: there while RETURN_REQUESTED or RETURNED, null otherwise. */
    var openReturnId: ReturnId? = null
        private set

    /** A return of it was closed or refunded: it is never returned again (one that was turned down doesn't count). */
    var returnSettled: Boolean = false
        private set

    /** What carrying out the open change did to the lines, until it is applied: its money, and the color put in. */
    private var changeWork: ChangeWork? = null

    private data class ChangeWork(val difference: Long, val variantId: VariantId?)

    /** Its lines now, by line id: a swapped line in its place, a line taken out gone. */
    val lines: List<OrderLine> get() = lineList.toList()

    /** Its totals as they stand now (the contract's OrderTotals); [totals] are the placed ones, which never change. */
    val totalsView: OrderTotalsView get() = OrderTotalsView.of(totals, lineList, codeDiscountRemoved, balancePaid, balanceDue, refunded)

    /** Placed and not paid yet: it may still be paid. */
    val isAwaitingPayment: Boolean get() = status.isAwaitingPayment

    /** Loading: rebuild from the stored events. */
    constructor(events: List<OrderDomainEvent>) : this() {
        applyEventStream(events)
    }

    /** A new payment attempt may start now ([PaymentPolicy.canStartPayment]). */
    fun canStartPayment(now: Instant): Boolean = PaymentPolicy.canStartPayment(status, holdUntil, now)

    /** An attempt to pay what a change left owed may start now ([PaymentPolicy.canPayBalance]). */
    val canPayBalance: Boolean get() = PaymentPolicy.canPayBalance(status, balanceDue)

    /** The attempt [paymentId] paid it, or paid what a change left owed: its money is the order's, not to be given back alone. */
    fun isPaidBy(paymentId: PaymentPublicId): Boolean = paidByPaymentId == paymentId || paymentId in balancePayments

    companion object {
        /** The longest before now the operator may say an order was handed to the courier. */
        val MAX_SHIPPED_AGO: Duration = Duration.ofDays(7)

        /**
         * A new order, unpaid, its stock to be held until [paymentPending] from [now]. With nothing to pay it is paid at
         * once: [OrderPlaced] then [OrderPaid] without a payment.
         *
         * [lines] are numbered 1 to n and hold each color once, and [totals] are theirs (OrderPricing worked them out
         * from the same lines): anything else is a bug and stops loudly. [limits] has the limit per order of each
         * product in the lines.
         */
        fun place(
            id: OrderId,
            code: OrderCode,
            userId: UserId,
            lines: List<OrderLine>,
            limits: Map<ProductId, MaxPerOrder>,
            address: AddressSnapshot,
            messenger: PreShipmentMessenger,
            shippingMethod: ShippingMethodCode,
            totals: OrderTotals,
            reserveRequested: Boolean,
            customerNote: CustomerNote?,
            gift: GiftNote?,
            paymentPending: Duration,
            by: UserId,
            now: Instant,
            reservationGroupId: ReservationGroupId? = null,
        ): Result<Order, PlaceFailure> {
            if (lines.isEmpty()) return Result.failure(PlaceFailure.NoLines)
            require(lines.map { it.lineId } == (1..lines.size).toList()) { "Order lines must be numbered 1 to n: ${lines.map { it.lineId }}" }
            require(lines.distinctBy { it.variantId }.size == lines.size) { "An order holds each color once" }
            require(totals.linesCount == lines.size && totals.itemsCount == lines.sumOf { it.quantity.value }) { "The totals count other lines" }
            require(totals.subtotal == lines.map { it.totals.total }.sum()) { "The totals are not of these lines" }
            require(paymentPending > Duration.ZERO) { "The time to pay must be positive: $paymentPending" }
            require(reservationGroupId == null || !reserveRequested) { "An order joining a reservation doesn't start its own" }

            val overLimit = productsOverLimit(lines.map { it.productId to it.quantity.value }, limits)
            if (overLimit.isNotEmpty()) return Result.failure(PlaceFailure.LimitExceeded(overLimit.toList()))

            val order = Order()
            order.start { sequence ->
                OrderPlaced(
                    id, sequence, now, by, code, userId, lines, address, messenger, shippingMethod, totals, reserveRequested,
                    holdUntil = now.plus(paymentPending), customerNote, gift, reservationGroupId,
                )
            }
            return Result.success(order)
        }

        /**
         * The products whose colors together are more than the product's limit per order: the rule [place] keeps, for
         * whoever needs to know it before (OrderDomainService names the cart's lines). [quantities] are (product, how
         * many) per line; [limits] has every product in them.
         */
        fun productsOverLimit(quantities: List<Pair<ProductId, Int>>, limits: Map<ProductId, MaxPerOrder>): Set<ProductId> =
            quantities.groupBy({ it.first }, { it.second })
                .filter { (productId, ofProduct) ->
                    val limit = limits[productId] ?: error("No limit per order for product ${productId.value}")
                    ofProduct.sum() > limit.value
                }
                .keys
    }

    /** The first event, numbered by this new order, and the payment of an order with nothing to pay. */
    private fun start(placed: (EventSequence) -> OrderPlaced) {
        val event = placed(nextSequence())
        raiseEvent(event)
        if (event.totals.payable == Money.ZERO) {
            raiseEvent(OrderPaid(id, nextSequence(), event.occurredOn, event.occurredByUserId, paymentId = null, amount = Money.ZERO, heldForReservation = false))
        }
    }

    // ============ the money ============

    /**
     * The money came in: an attempt was confirmed by its gateway. Only while unpaid, and only exactly its payable (a
     * different amount is a bug: it is never paid that way). A callback after [holdUntil] is still taken, as long as
     * the order hasn't expired.
     *
     * @param heldForReservation it is held with «رزرو ۴ روزه» (RESERVED) instead of being prepared now (PROCESSING);
     *   decided by the caller with what is true now (ReservationDomainService), and kept in the event as decided.
     * @param reservationGroupId the group it is held in, or ships with; a group it joined when placed stays its own.
     */
    fun markPaid(
        paymentId: PaymentPublicId,
        amount: Money,
        heldForReservation: Boolean,
        by: UserId,
        now: Instant,
        reservationGroupId: ReservationGroupId? = null,
    ): Result<Order, MarkPaidFailure> {
        if (status.isPaid) return Result.failure(MarkPaidFailure.AlreadyPaid)
        if (!isAwaitingPayment) return Result.failure(MarkPaidFailure.NotPayable(status))
        if (amount != totals.payable) return Result.failure(MarkPaidFailure.AmountMismatch(expected = totals.payable, paid = amount))
        require(!heldForReservation || reservationGroupId != null) { "Order ${code.value} held for a reservation without its group" }
        require(this.reservationGroupId == null || reservationGroupId == this.reservationGroupId) { "Order ${code.value} joined another group when placed" }

        raiseEvent(OrderPaid(id, nextSequence(), now, by, paymentId, amount, heldForReservation, reservationGroupId))
        return Result.success(this)
    }

    /**
     * Its reservation's time is over: a held order goes to be prepared, with the rest of its group. Only a RESERVED
     * order moves; any other (let go already, or never held) changes nothing, so the job may run twice.
     */
    fun releaseFromReservation(by: UserId, now: Instant): Result<Order, Nothing> {
        if (status != RESERVED) return Result.success(this)
        raiseEvent(OrderReleasedFromReservation(id, nextSequence(), now, by))
        return Result.success(this)
    }

    /**
     * A payment attempt failed or was cancelled. Recorded once per attempt, and only while the order is unpaid: on a
     * paid or expired order, or for an attempt recorded already (a callback that came twice), it changes nothing.
     * [reason] is one that fails the order: never an order that couldn't be paid any more, nor one that expired.
     */
    fun recordPaymentFailure(paymentId: PaymentPublicId, reason: PaymentFailureReason, by: UserId, now: Instant): Result<Order, Nothing> {
        require(reason != PaymentFailureReason.ORDER_NOT_PAYABLE && reason != PaymentFailureReason.EXPIRED) { "$reason doesn't fail an order" }
        if (!isAwaitingPayment || paymentId in failedAttempts) return Result.success(this)

        raiseEvent(OrderPaymentFailed(id, nextSequence(), now, by, paymentId, reason))
        return Result.success(this)
    }

    /**
     * Not paid in time: the order expires, and its stock is to be given back. Only while unpaid and from [holdUntil] on;
     * an expired order expiring again changes nothing. Whether an attempt is still on the gateway's page is the
     * caller's to check first (PaymentPolicy.GATEWAY_WINDOW): the payments are not this aggregate's.
     */
    fun expire(by: UserId, now: Instant): Result<Order, ExpireFailure> {
        if (status == EXPIRED) return Result.success(this)
        if (!isAwaitingPayment) return Result.failure(ExpireFailure.NotAwaitingPayment(status))
        if (now.isBefore(holdUntil)) return Result.failure(ExpireFailure.TooEarly(holdUntil))

        raiseEvent(OrderExpired(id, nextSequence(), now, by))
        return Result.success(this)
    }

    // ============ the photos before shipping ============

    /**
     * The operator's photos of the packed bag replace the draft as a whole. Only while it is being prepared (PROCESSING)
     * and its photos wait to be sent (WAITING). Each photo of a line must show one of this order's lines. [items] are 1
     * to PreShipmentPolicy.MAX_PHOTOS, each upload once: the use case checked that, so anything else is a bug.
     */
    fun attachPhotos(items: List<OrderPhoto>, by: UserId, now: Instant): Result<Order, AttachPhotosFailure> {
        require(items.size in 1..PreShipmentPolicy.MAX_PHOTOS) { "Order ${code.value}: ${items.size} photos" }
        require(items.distinctBy { it.mediaId }.size == items.size) { "Order ${code.value}: a photo twice" }
        if (!inPhotoStep()) return Result.failure(AttachPhotosFailure.NotInPhotoStep(status))
        val lineIds = lineList.map { it.lineId }.toSet()
        items.withIndex().firstOrNull { (_, photo) -> photo.lineId != null && photo.lineId !in lineIds }?.let { (index, photo) ->
            return Result.failure(AttachPhotosFailure.UnknownLine(index, photo.lineId!!))
        }

        raiseEvent(OrderPhotosAttached(id, nextSequence(), now, by, items))
        return Result.success(this)
    }

    /**
     * The draft was sent to the customer, by hand on [channel]: PHOTO_SENT, waiting for their answer for [feedbackHours].
     * Only while PROCESSING with photos WAITING and a draft; an order of several lines needs a photo of each line.
     */
    fun sendPhotos(channel: MessengerChannel, message: PhotoMessage?, feedbackHours: Int, by: UserId, now: Instant): Result<Order, SendPhotosFailure> {
        require(feedbackHours > 0) { "The time to answer the photos must be positive: $feedbackHours" }
        if (!inPhotoStep() || photoDraft.isEmpty()) return Result.failure(SendPhotosFailure.NotInPhotoStep(status))
        if (lineList.size > 1) {
            val shown = photoDraft.mapNotNull { it.lineId }.toSet()
            val missing = lineList.map { it.lineId }.filter { it !in shown }
            if (missing.isNotEmpty()) return Result.failure(SendPhotosFailure.PhotosIncomplete(missing))
        }

        val deadline = now.plus(Duration.ofHours(feedbackHours.toLong()))
        raiseEvent(OrderPhotosSent(id, nextSequence(), now, by, photoBatch + 1, channel, manual = true, message, deadline))
        return Result.success(this)
    }

    /**
     * The photos sent are approved: by the customer, or ([automatic]) by the system once [feedbackDeadline] has passed.
     * Only while PHOTO_SENT with photos SENT; approving photos approved already changes nothing.
     */
    fun approvePhotos(automatic: Boolean, by: UserId, now: Instant): Result<Order, ApprovePhotosFailure> {
        if (status == OrderStatus.PHOTO_SENT && photoStatus == PhotoStatus.APPROVED) return Result.success(this)
        if (status != OrderStatus.PHOTO_SENT || photoStatus != PhotoStatus.SENT) return Result.failure(ApprovePhotosFailure.NotAwaitingApproval(status))
        val deadline = checkNotNull(feedbackDeadline) { "Order ${code.value}: photos sent without a deadline" }
        if (automatic && now.isBefore(deadline)) return Result.failure(ApprovePhotosFailure.NotDue(deadline))

        raiseEvent(OrderPhotosApproved(id, nextSequence(), now, by, automatic))
        return Result.success(this)
    }

    /** Photos may be drafted and sent: being prepared, nothing sent since the last change. */
    private fun inPhotoStep(): Boolean = status == OrderStatus.PROCESSING && photoStatus == PhotoStatus.WAITING

    // ============ shipping and delivery ============

    /**
     * The order left with the courier: SHIPPED. Only from PHOTO_SENT, with the carrier it was paid for ([shippingMethod]),
     * handed over at [shippedOn] (not after [now], not more than [MAX_SHIPPED_AGO] before, not before its photos were sent),
     * only once its photos are approved or the customer's time to answer is over, and only when nothing is owed after a
     * change ([balanceDue], phase 3). [reservationGroupId] is the group it ships with under the same code, if any.
     */
    fun ship(
        carrier: ShippingMethodCode,
        trackingCode: TrackingCode,
        shippedOn: Instant,
        showInDailyList: Boolean,
        by: UserId,
        now: Instant,
        reservationGroupId: ReservationGroupId? = null,
    ): Result<Order, ShipFailure> {
        if (status != OrderStatus.PHOTO_SENT) return Result.failure(ShipFailure.InvalidTransition(transitionRefused(status, OrderStatus.SHIPPED)))
        if (carrier != shippingMethod) return Result.failure(ShipFailure.CarrierMismatch(shippingMethod))
        if (!shippedOnFits(shippedOn, now)) return Result.failure(ShipFailure.ShippedOnInvalid)
        val deadline = checkNotNull(feedbackDeadline) { "Order ${code.value}: photos sent without a deadline" }
        if (photoStatus != PhotoStatus.APPROVED && now.isBefore(deadline)) return Result.failure(ShipFailure.AwaitingFeedback(deadline))
        if (balanceDue > Money.ZERO) return Result.failure(ShipFailure.BalanceDue(balanceDue))

        raiseEvent(OrderShipped(id, nextSequence(), now, by, carrier, trackingCode, shippedOn, showInDailyList, reservationGroupId))
        return Result.success(this)
    }

    /**
     * A mistake in the parcel of a SHIPPED order put right: its tracking code, when it was handed over (the same rule as
     * [ship]), or whether it is listed daily. The same parcel again changes nothing.
     */
    fun correctShipment(trackingCode: TrackingCode, shippedOn: Instant, showInDailyList: Boolean, by: UserId, now: Instant): Result<Order, CorrectShipmentFailure> {
        if (status != OrderStatus.SHIPPED) return Result.failure(CorrectShipmentFailure.InvalidTransition(transitionRefused(status, OrderStatus.SHIPPED)))
        if (!shippedOnFits(shippedOn, now)) return Result.failure(CorrectShipmentFailure.ShippedOnInvalid)
        val current = checkNotNull(shipment) { "Order ${code.value} shipped without its parcel" }
        if (current.trackingCode == trackingCode && current.shippedOn == shippedOn && current.showInDailyList == showInDailyList) return Result.success(this)

        raiseEvent(OrderShipmentCorrected(id, nextSequence(), now, by, trackingCode, shippedOn, showInDailyList))
        return Result.success(this)
    }

    /**
     * The customer has it: DELIVERED, now. Only from SHIPPED. Its time to return ends [returnWindowDays] from now (the
     * shop's window of now, kept), when a line of it is returnable.
     */
    fun markDelivered(returnWindowDays: Int, note: StatusNote?, by: UserId, now: Instant): Result<Order, InvalidTransition> {
        require(returnWindowDays >= 0) { "A negative return window: $returnWindowDays" }
        if (status != OrderStatus.SHIPPED) return Result.failure(transitionRefused(status, OrderStatus.DELIVERED))
        val returnable = now.plus(Duration.ofDays(returnWindowDays.toLong())).takeIf { lineList.any { it.returnable } }

        raiseEvent(OrderDelivered(id, nextSequence(), now, by, returnable, note))
        return Result.success(this)
    }

    /** Handed over at [shippedOn]: not after [now], within [MAX_SHIPPED_AGO] of it, and not before the photos were sent. */
    private fun shippedOnFits(shippedOn: Instant, now: Instant): Boolean {
        val sentOn = checkNotNull(photosSentOn) { "Order ${code.value} ships without photos sent" }
        return !shippedOn.isAfter(now) && !shippedOn.isBefore(now.minus(MAX_SHIPPED_AGO)) && !shippedOn.isBefore(sentOn)
    }

    // ============ cancelling and refunds ============

    /**
     * Cancelled before it shipped: CANCELLED, once. The customer ([who] CUSTOMER, with a [reason]) only while its status
     * hasn't moved (OrderTransitionPolicy.customerMayCancel); staff from any status before SHIPPED. Giving back its stock
     * and its money is OrderCancellation's, in the same transaction.
     */
    fun cancel(who: CancelledBy, reason: CancelReason?, note: StatusNote?, by: UserId, now: Instant): Result<Order, CancelFailure> {
        require((who == CancelledBy.CUSTOMER) == (reason != null)) { "A customer cancels with a reason, staff without one" }
        when (who) {
            CancelledBy.CUSTOMER ->
                if (!OrderTransitionPolicy.customerMayCancel(status, photoEverSent)) return Result.failure(CancelFailure.NotCancellable(status))
            CancelledBy.STAFF ->
                if (status !in OrderTransitionPolicy.STAFF_CANCELLABLE) return Result.failure(CancelFailure.InvalidTransition(transitionRefused(status, OrderStatus.CANCELLED)))
        }

        raiseEvent(OrderCancelled(id, nextSequence(), now, by, who, reason, note))
        return Result.success(this)
    }

    /**
     * The refund [refundId] of [amount] was completed: it adds to what went back. The same refund again changes nothing.
     * More back than the order was paid is a bug (RefundDomainService counts that before): it stops loudly.
     */
    fun recordRefund(refundId: RefundId, amount: Money, by: UserId, now: Instant): Result<Order, Nothing> {
        if (refundId in refundIds) return Result.success(this)
        check(status.isPaid) { "Order ${code.value} is $status: nothing was paid to give back" }
        val paid = totals.payable + balancePaid
        check(refunded + amount <= paid) { "Order ${code.value}: refunding $amount on top of $refunded is more than its $paid" }

        raiseEvent(OrderRefunded(id, nextSequence(), now, by, refundId, amount))
        return Result.success(this)
    }

    // ============ a change request after the photos (phase 3) ============

    /**
     * The customer asks for a change after seeing the photos: CHANGE_REQUESTED, its photos too. Only while PHOTO_SENT with
     * its photos SENT or APPROVED (OrderTransitionPolicy.customerMayRequestChange), so one at a time and never after it
     * ships. The line it names must be one of the order's lines now. A COLOR change asks for another color of the line's
     * product, a MODEL change for a color of another product: [target] is that color as the catalog has it (the use
     * case found it), for those two only.
     */
    fun requestChange(request: ChangeRequest, target: ChangeTarget?, by: UserId, now: Instant): Result<Order, RequestChangeFailure> {
        require(request.type.swapsColor == (target != null)) { "A ${request.type} change ${if (request.type.swapsColor) "needs" else "has no"} target" }
        require(target == null || target.variantId == request.desiredVariantId) { "The target is not the color asked for" }
        if (!OrderTransitionPolicy.customerMayRequestChange(status, photoStatus)) return Result.failure(RequestChangeFailure.NotAllowed(status))
        val line = request.lineId?.let { lineId -> lineList.firstOrNull { it.lineId == lineId } ?: return Result.failure(RequestChangeFailure.UnknownLine(lineId)) }
        if (target != null && !fitsChange(request.type, checkNotNull(line), target.productId, target.variantId)) {
            return Result.failure(RequestChangeFailure.DesiredVariantInvalid)
        }

        raiseEvent(OrderChangeRequested(id, nextSequence(), now, by, request.type, request.lineId, request.desiredVariantId, request.note))
        return Result.success(this)
    }

    /**
     * Staff turn the open change request down, saying why in [note] (the customer reads it): the same bag ships. Back to
     * PROCESSING, the photos WAITING for a new batch. Only while CHANGE_REQUESTED.
     */
    fun rejectChange(note: StatusNote?, by: UserId, now: Instant): Result<Order, ResolveChangeFailure> {
        if (status != OrderStatus.CHANGE_REQUESTED) return Result.failure(ResolveChangeFailure.InvalidTransition(transitionRefused(status, PROCESSING)))
        if (note == null) return Result.failure(ResolveChangeFailure.NoteRequired)

        raiseEvent(OrderChangeRejected(id, nextSequence(), now, by, note))
        return Result.success(this)
    }

    /**
     * Carrying out an open COLOR or MODEL change: its line becomes [newLine], made with OrderLine.replacedBy from the color
     * put in (the one asked for, or another staff chose) at its price today. That color must still fit the change (another
     * color of the same product, or one of another product), and the new product's colors in the order together stay
     * within [newProductLimit]. What it costs more or less is worked out now and settled by [applyChange], which follows.
     * Only while CHANGE_REQUESTED, once per change.
     */
    fun replaceLine(newLine: OrderLine, newProductLimit: MaxPerOrder, by: UserId, now: Instant): Result<Order, ReplaceLineFailure> {
        if (status != OrderStatus.CHANGE_REQUESTED) return Result.failure(ReplaceLineFailure.InvalidTransition(transitionRefused(status, PROCESSING)))
        val change = checkNotNull(openChange) { "Order ${code.value} is CHANGE_REQUESTED without a change" }
        require(change.type.swapsColor && changeWork == null) { "Order ${code.value}: a ${change.type} change doesn't swap a line now" }
        val old = lineList.first { it.lineId == change.lineId }
        require(newLine.lineId == old.lineId && newLine.quantity == old.quantity) { "A swapped line keeps its number and quantity" }
        if (!fitsChange(change.type, old, newLine.productId, newLine.variantId)) return Result.failure(ReplaceLineFailure.VariantInvalid)
        val ofNewProduct = lineList.filter { it.lineId != old.lineId && it.productId == newLine.productId }.sumOf { it.quantity.value } + newLine.quantity.value
        if (ofNewProduct > newProductLimit.value) return Result.failure(ReplaceLineFailure.LimitExceeded(newLine.productId))

        raiseEvent(OrderLineReplaced(id, nextSequence(), now, by, old.lineId, newLine, ChangeRequestPolicy.priceDifference(old, newLine.priceTag)))
        return Result.success(this)
    }

    /**
     * Carrying out an open CANCEL_ITEM change on an order of several lines: its line is taken out, and what the customer
     * paid for it (ChangeRequestPolicy.paidShareOf) is settled by [applyChange], which follows. The only line of an order
     * is never taken out: that is the whole order cancelled (OrderCancellation). Only while CHANGE_REQUESTED, once per
     * change.
     */
    fun removeLine(by: UserId, now: Instant): Result<Order, ResolveChangeFailure> {
        if (status != OrderStatus.CHANGE_REQUESTED) return Result.failure(ResolveChangeFailure.InvalidTransition(transitionRefused(status, PROCESSING)))
        val change = checkNotNull(openChange) { "Order ${code.value} is CHANGE_REQUESTED without a change" }
        require(change.type == ChangeType.CANCEL_ITEM && changeWork == null) { "Order ${code.value}: a ${change.type} change doesn't take a line out now" }
        require(lineList.size > 1) { "Order ${code.value}: its only line is the whole order cancelled, not taken out" }
        val line = lineList.first { it.lineId == change.lineId }

        raiseEvent(OrderLineRemoved(id, nextSequence(), now, by, line.lineId, ChangeRequestPolicy.paidShareOf(line, totals)))
        return Result.success(this)
    }

    /**
     * The open change is carried out: back to PROCESSING, the photos WAITING for a new batch. A COLOR or MODEL change
     * after [replaceLine], a CANCEL_ITEM after [removeLine], an OTHER alone with a [note] of what was done. Its money is
     * settled now (ChangeRequestPolicy.settle): what costs more is owed; what costs less is first taken off what is owed,
     * and the rest is to go back. Returns that settlement: the use case opens the refund. Only while CHANGE_REQUESTED.
     */
    fun applyChange(note: StatusNote?, by: UserId, now: Instant): Result<ChangeRequestPolicy.Settlement, ResolveChangeFailure> {
        if (status != OrderStatus.CHANGE_REQUESTED) return Result.failure(ResolveChangeFailure.InvalidTransition(transitionRefused(status, PROCESSING)))
        val change = checkNotNull(openChange) { "Order ${code.value} is CHANGE_REQUESTED without a change" }
        if (change.type == ChangeType.OTHER && note == null) return Result.failure(ResolveChangeFailure.NoteRequired)
        val work = changeWork
        require((work != null) == (change.type != ChangeType.OTHER)) { "Order ${code.value}: a ${change.type} change applied ${if (work == null) "before" else "after"} its work" }

        val settlement = ChangeRequestPolicy.settle(balanceDue, work?.difference ?: 0)
        raiseEvent(OrderChangeApplied(id, nextSequence(), now, by, change.type, work?.variantId, note, settlement.balanceDueAfter))
        return Result.success(settlement)
    }

    /**
     * What the order owes after a change was paid by the attempt [paymentId] (purpose BALANCE): exactly [balanceDue], or
     * the gateway confirmed another amount (a bug, never a payment). Nothing owed is ORDER_ALREADY_PAID. The same attempt
     * again changes nothing.
     */
    fun payBalance(paymentId: PaymentPublicId, amount: Money, by: UserId, now: Instant): Result<Order, PayBalanceFailure> {
        if (paymentId in balancePayments) return Result.success(this)
        if (balanceDue == Money.ZERO) return Result.failure(PayBalanceFailure.NoBalanceDue)
        if (amount != balanceDue) return Result.failure(PayBalanceFailure.AmountMismatch(expected = balanceDue, paid = amount))

        raiseEvent(OrderBalancePaid(id, nextSequence(), now, by, paymentId, amount))
        return Result.success(this)
    }

    // ============ a return after the delivery (phase 7) ============

    /**
     * Why a return may not be asked for at [now], or null when it may: not DELIVERED (a return open already, or never
     * delivered), returned once already ([returnSettled]) or nothing returnable ([RequestReturnFailure.NotReturnable]),
     * or after [returnableUntil] ([RequestReturnFailure.WindowExpired]). The time is checked only here, when it is asked
     * for: the steps after it have none.
     */
    fun returnRefusal(now: Instant): RequestReturnFailure? {
        val until = returnableUntil
        return when {
            status != OrderStatus.DELIVERED || returnSettled || until == null -> RequestReturnFailure.NotReturnable(status)
            now.isAfter(until) -> RequestReturnFailure.WindowExpired(until)
            else -> null
        }
    }

    /**
     * The customer asks to send [items] back: RETURN_REQUESTED, the return [returnId] open. Only while a return may be
     * asked for ([returnRefusal]); [items] must be its lines, each once, returnable when bought and no more than bought
     * (ReturnPolicy.itemProblems: 400, each under its field).
     */
    fun requestReturn(returnId: ReturnId, items: List<ReturnItem>, by: UserId, now: Instant): Result<Order, RequestReturnFailure> {
        require(items.isNotEmpty()) { "Order ${code.value}: a return of nothing" }
        returnRefusal(now)?.let { return Result.failure(it) }
        val problems = ReturnPolicy.itemProblems(lineList, items)
        if (problems.isNotEmpty()) return Result.failure(RequestReturnFailure.ItemsInvalid(problems))

        raiseEvent(OrderReturnRequested(id, nextSequence(), now, by, returnId))
        return Result.success(this)
    }

    /**
     * The open return [returnId]'s parcel reached the warehouse: RETURNED. Only while RETURN_REQUESTED with that return
     * open; anything else is a bug (the use case checked the return first): it stops loudly.
     */
    fun receiveReturn(returnId: ReturnId, by: UserId, now: Instant): Result<Order, Nothing> {
        check(status == OrderStatus.RETURN_REQUESTED && openReturnId == returnId) {
            "Order ${code.value} is $status with return ${openReturnId?.value} open: return ${returnId.value} can't arrive"
        }
        raiseEvent(OrderReturnReceived(id, nextSequence(), now, by, returnId))
        return Result.success(this)
    }

    /**
     * The open return [returnId] ended with [outcome]: REJECTED or CLOSED bring the order back to DELIVERED, REFUNDED makes
     * it REFUNDED (only after its parcel arrived). Closed or refunded, it is never returned again. Anything else is a bug
     * (the use case checked the return first): it stops loudly.
     */
    fun endReturn(returnId: ReturnId, outcome: ReturnOutcome, by: UserId, now: Instant): Result<Order, Nothing> {
        val fits = when (outcome) {
            ReturnOutcome.REJECTED -> status == OrderStatus.RETURN_REQUESTED
            ReturnOutcome.CLOSED -> status == OrderStatus.RETURN_REQUESTED || status == OrderStatus.RETURNED
            ReturnOutcome.REFUNDED -> status == OrderStatus.RETURNED
        }
        check(fits && openReturnId == returnId) { "Order ${code.value} is $status with return ${openReturnId?.value} open: return ${returnId.value} can't end $outcome" }
        raiseEvent(OrderReturnEnded(id, nextSequence(), now, by, returnId, outcome))
        return Result.success(this)
    }

    // ============ deleting the account (phase 14) ============

    /** Its personal data was erased ([erasePersonalData]). */
    val isErased: Boolean get() = address.isErased

    /**
     * Erases what is personal of the order, for [reason] (decision 2 of phase 14): the recipient, their phone, the
     * address line, plaque, unit and postal code (the province and city stay, for the reports), the messenger's number,
     * handle and note (its channel stays), the customer note and the gift message. Its money, lines, tracking code and
     * dates stay. Whatever its status: whether its customer may delete their account (no active order) is the use
     * case's question. Erasing an erased order again records nothing.
     */
    fun erasePersonalData(reason: ErasureReason, by: UserId, now: Instant): Result<Order, Nothing> {
        if (isErased) return Result.success(this)
        raiseEvent(OrderPersonalDataErased(id, nextSequence(), now, by, reason))
        return Result.success(this)
    }

    /** [variantId] of [productId] fits a [type] change of [line]: another color of its product (COLOR), or of another product (MODEL). */
    private fun fitsChange(type: ChangeType, line: OrderLine, productId: ProductId, variantId: VariantId): Boolean = when (type) {
        ChangeType.COLOR -> productId == line.productId && variantId != line.variantId
        ChangeType.MODEL -> productId != line.productId
        ChangeType.CANCEL_ITEM, ChangeType.OTHER -> error("A $type change puts no color in")
    }

    /** The open change is over, whichever way: being prepared again, new photos to take. */
    private fun closeChange() {
        openChange = null
        changeWork = null
        status = PROCESSING
        photoStatus = PhotoStatus.WAITING
    }

    /** [from] doesn't go to [to] through `POST …/status`, or by this operation: what it may go to instead. */
    private fun transitionRefused(from: OrderStatus, to: OrderStatus) = InvalidTransition(from, to, OrderTransitionPolicy.allowedFrom(from))

    override fun apply(event: OrderDomainEvent) {
        id = event.orderId
        when (event) {
            is OrderPlaced -> {
                code = event.code
                userId = event.userId
                lineList += event.lines
                address = event.address
                messenger = event.messenger
                shippingMethod = event.shippingMethod
                totals = event.totals
                reserveRequested = event.reserveRequested
                placedOn = event.occurredOn
                holdUntil = event.holdUntil
                customerNote = event.customerNote
                gift = event.gift
                reservationGroupId = event.reservationGroupId
                status = PENDING_PAYMENT
            }
            is OrderPaymentFailed -> {
                failedAttempts += event.paymentId
                status = PAYMENT_FAILED
            }
            is OrderPaid -> {
                paidOn = event.occurredOn
                paidByPaymentId = event.paymentId
                reservationGroupId = event.reservationGroupId ?: reservationGroupId
                status = if (event.heldForReservation) RESERVED else PROCESSING
            }
            is OrderExpired -> status = EXPIRED
            is OrderReleasedFromReservation -> status = PROCESSING
            is OrderPhotosAttached -> photoDraft = event.items
            is OrderPhotosSent -> {
                photosSent = photoDraft
                photoDraft = emptyList()
                photoBatch = event.batchNo
                photoStatus = PhotoStatus.SENT
                photoEverSent = true
                photosSentOn = event.occurredOn
                feedbackDeadline = event.feedbackDeadline
                status = OrderStatus.PHOTO_SENT
            }
            is OrderPhotosApproved -> photoStatus = PhotoStatus.APPROVED
            is OrderShipped -> {
                shipment = OrderShipment(event.carrier, event.trackingCode, event.shippedOn, event.showInDailyList)
                status = OrderStatus.SHIPPED
            }
            is OrderShipmentCorrected -> {
                val current = checkNotNull(shipment) { "Broken stream of order ${event.orderId.value}: a correction before it shipped" }
                shipment = current.copy(trackingCode = event.trackingCode, shippedOn = event.shippedOn, showInDailyList = event.showInDailyList)
            }
            is OrderDelivered -> {
                deliveredOn = event.occurredOn
                returnableUntil = event.returnableUntil
                status = OrderStatus.DELIVERED
            }
            is OrderCancelled -> {
                cancellation = OrderCancellationInfo(event.by, event.reason, event.note, event.occurredOn)
                status = OrderStatus.CANCELLED
                // A cancelled order owes nothing, and its open change is answered by the cancellation.
                openChange = null
                changeWork = null
                balanceDue = Money.ZERO
            }
            is OrderRefunded -> {
                refundIds += event.refundId
                refunded += event.amount
            }
            is OrderChangeRequested -> {
                openChange = ChangeRequest(event.changeType, event.lineId, event.desiredVariantId, event.note)
                status = OrderStatus.CHANGE_REQUESTED
                photoStatus = PhotoStatus.CHANGE_REQUESTED
            }
            is OrderChangeRejected -> closeChange()
            is OrderLineReplaced -> {
                val index = lineList.indexOfFirst { it.lineId == event.lineId }
                check(index >= 0) { "Broken stream of order ${event.orderId.value}: line ${event.lineId} swapped but not there" }
                lineList[index] = event.line
                changeWork = ChangeWork(event.priceDifference, event.line.variantId)
            }
            is OrderLineRemoved -> {
                val line = checkNotNull(lineList.firstOrNull { it.lineId == event.lineId }) {
                    "Broken stream of order ${event.orderId.value}: line ${event.lineId} taken out but not there"
                }
                codeDiscountRemoved += line.totals.total - event.paidShare
                lineList.remove(line)
                changeWork = ChangeWork(-event.paidShare.toman, null)
            }
            is OrderChangeApplied -> {
                val settlement = ChangeRequestPolicy.settle(balanceDue, changeWork?.difference ?: 0)
                // Second wall: the event keeps what the settlement gave then; a stream that disagrees is broken.
                check(settlement.balanceDueAfter == event.balanceDueAfter) {
                    "Broken stream of order ${event.orderId.value}: a change settled to ${event.balanceDueAfter}, not ${settlement.balanceDueAfter}"
                }
                balanceDue = event.balanceDueAfter
                differenceRefunded += settlement.refund
                closeChange()
            }
            is OrderBalancePaid -> {
                balancePayments += event.paymentId
                balancePaid += event.amount
                balanceDue = Money.ZERO
            }
            is OrderReturnRequested -> {
                openReturnId = event.returnId
                status = OrderStatus.RETURN_REQUESTED
            }
            is OrderReturnReceived -> {
                check(openReturnId == event.returnId) { "Broken stream of order ${event.orderId.value}: return ${event.returnId.value} arrived, ${openReturnId?.value} is open" }
                status = OrderStatus.RETURNED
            }
            is OrderReturnEnded -> {
                check(openReturnId == event.returnId) { "Broken stream of order ${event.orderId.value}: return ${event.returnId.value} ended, ${openReturnId?.value} is open" }
                openReturnId = null
                returnSettled = returnSettled || event.outcome.settlesReturn
                status = if (event.outcome == ReturnOutcome.REFUNDED) OrderStatus.REFUNDED else OrderStatus.DELIVERED
            }
            is OrderPersonalDataErased -> {
                address = address.erased()
                messenger = messenger.erased()
                customerNote = null
                gift = gift?.erased()
            }
        }
    }

    override fun equals(other: Any?): Boolean = other is Order && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Order(id=${id.value}, code=${code.value}, status=$status)"

    // ============ failures, one sealed family per operation ============

    sealed class PlaceFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Nothing to order. */
        data object NoLines : PlaceFailure("CART_EMPTY", FailureKind.BUSINESS_RULE)

        /** The colors of these products together are more than the product's limit per order. */
        data class LimitExceeded(val productIds: List<ProductId>) :
            PlaceFailure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, mapOf("productIds" to productIds.map { it.value }))
    }

    sealed class MarkPaidFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        data object AlreadyPaid : MarkPaidFailure("ORDER_ALREADY_PAID", FailureKind.BUSINESS_RULE)

        /** Expired (or, in a later phase, cancelled): it can't be paid any more. */
        data class NotPayable(val status: OrderStatus) :
            MarkPaidFailure("ORDER_NOT_PAYABLE", FailureKind.BUSINESS_RULE, mapOf("status" to status.name))

        /** The gateway confirmed another amount than the order's payable: a bug, never a payment. */
        data class AmountMismatch(val expected: Money, val paid: Money) :
            MarkPaidFailure("PAYMENT_AMOUNT_MISMATCH", FailureKind.CONFLICT, mapOf("expected" to expected.toman, "paid" to paid.toman))
    }

    sealed class ExpireFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Paid (or, in a later phase, cancelled): nothing to expire. */
        data class NotAwaitingPayment(val status: OrderStatus) :
            ExpireFailure("ORDER_NOT_AWAITING_PAYMENT", FailureKind.CONFLICT, mapOf("status" to status.name))

        /** Its time to pay hasn't ended yet. */
        data class TooEarly(val holdUntil: Instant) :
            ExpireFailure("ORDER_NOT_DUE", FailureKind.CONFLICT, mapOf("holdUntil" to holdUntil.toString()))
    }

    /**
     * The order can't go from [from] to [to] (422 INVALID_STATUS_TRANSITION); [allowed] is where `POST …/status` may take
     * it instead (OrderTransitionPolicy).
     */
    data class InvalidTransition(val from: OrderStatus, val to: OrderStatus, val allowed: Set<OrderStatus>) : ResultFailure(
        "INVALID_STATUS_TRANSITION",
        FailureKind.BUSINESS_RULE,
        meta = mapOf("from" to from.name, "to" to to.name, "allowed" to allowed.map { it.name }.sorted()),
    )

    sealed class AttachPhotosFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        /** Not being prepared with its photos waiting (422). */
        data class NotInPhotoStep(val status: OrderStatus) :
            AttachPhotosFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, meta = mapOf("from" to status.name))

        /** The [index]-th photo shows a line the order doesn't have (400 on `items.{index}.orderItemId`). */
        data class UnknownLine(val index: Int, val lineId: Int) :
            AttachPhotosFailure("ORDER_ITEM_NOT_FOUND", FailureKind.INVALID, "items.$index.orderItemId", mapOf("orderItemId" to lineId))
    }

    sealed class SendPhotosFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Not being prepared with its photos waiting, or no draft to send (422). */
        data class NotInPhotoStep(val status: OrderStatus) :
            SendPhotosFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, mapOf("from" to status.name))

        /** These lines have no photo of their own (422). */
        data class PhotosIncomplete(val missingLineIds: List<Int>) :
            SendPhotosFailure("PHOTOS_INCOMPLETE", FailureKind.BUSINESS_RULE, mapOf("missingItemIds" to missingLineIds))
    }

    sealed class ApprovePhotosFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** No photos sent wait for an answer (422). */
        data class NotAwaitingApproval(val status: OrderStatus) :
            ApprovePhotosFailure("INVALID_STATUS_TRANSITION", FailureKind.BUSINESS_RULE, mapOf("from" to status.name))

        /** The system approves only once the customer's time to answer is over. */
        data class NotDue(val feedbackDeadline: Instant) :
            ApprovePhotosFailure("PHOTOS_NOT_DUE", FailureKind.CONFLICT, mapOf("feedbackDeadline" to feedbackDeadline.toString()))
    }

    sealed class ShipFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data class InvalidTransition(val transition: Order.InvalidTransition) :
            ShipFailure(transition.code, transition.kind, meta = transition.meta)

        /** Another carrier than the one the customer paid for (400 on `carrier`). */
        data class CarrierMismatch(val shippingMethod: ShippingMethodCode) :
            ShipFailure("CARRIER_MISMATCH", FailureKind.INVALID, "carrier", mapOf("shippingMethod" to shippingMethod.name))

        /** Handed over in the future, too long ago, or before the photos were sent (400 on `shippedAt`). */
        data object ShippedOnInvalid : ShipFailure("SHIPPED_AT_INVALID", FailureKind.INVALID, "shippedAt")

        /** The customer may still answer the photos until [feedbackDeadline] (422). */
        data class AwaitingFeedback(val feedbackDeadline: Instant) :
            ShipFailure("AWAITING_CUSTOMER_FEEDBACK", FailureKind.BUSINESS_RULE, meta = mapOf("feedbackDeadline" to feedbackDeadline.toString()))

        /** [balanceDue] is still owed after a change to a dearer color (422). */
        data class BalanceDue(val balanceDue: Money) :
            ShipFailure("BALANCE_DUE", FailureKind.BUSINESS_RULE, meta = mapOf("balanceDue" to balanceDue.toman))
    }

    sealed class CorrectShipmentFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data class InvalidTransition(val transition: Order.InvalidTransition) :
            CorrectShipmentFailure(transition.code, transition.kind, meta = transition.meta)

        data object ShippedOnInvalid : CorrectShipmentFailure("SHIPPED_AT_INVALID", FailureKind.INVALID, "shippedAt")
    }

    sealed class CancelFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** The customer's: its status has moved (or it is cancelled already, or not paid) (422 ORDER_NOT_CANCELLABLE). */
        data class NotCancellable(val status: OrderStatus) :
            CancelFailure("ORDER_NOT_CANCELLABLE", FailureKind.BUSINESS_RULE, mapOf("status" to status.name))

        /** Staff's: shipped already, or not paid (422 INVALID_STATUS_TRANSITION). */
        data class InvalidTransition(val transition: Order.InvalidTransition) : CancelFailure(transition.code, transition.kind, transition.meta)
    }

    sealed class RequestChangeFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        /** Not after photos the customer saw, a change is open already, or it shipped (422). */
        data class NotAllowed(val status: OrderStatus) :
            RequestChangeFailure("CHANGE_REQUEST_NOT_ALLOWED", FailureKind.BUSINESS_RULE, meta = mapOf("status" to status.name))

        /** The line named isn't one of the order's lines now (400 on `orderItemId`). */
        data class UnknownLine(val lineId: Int) :
            RequestChangeFailure("ORDER_ITEM_NOT_FOUND", FailureKind.INVALID, "orderItemId", mapOf("orderItemId" to lineId))

        /** COLOR with a color of another product or the line's own, MODEL with a color of the same product (400). */
        data object DesiredVariantInvalid : RequestChangeFailure("DESIRED_VARIANT_INVALID", FailureKind.INVALID, "desiredVariantId")
    }

    /** Resolving a change request: rejecting it, taking a line out, applying it. */
    sealed class ResolveChangeFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        /** No change request is open (422). */
        data class InvalidTransition(val transition: Order.InvalidTransition) :
            ResolveChangeFailure(transition.code, transition.kind, meta = transition.meta)

        /** A rejection says why, and an OTHER change what was done (400 on `note`). */
        data object NoteRequired : ResolveChangeFailure("NOTE_REQUIRED", FailureKind.INVALID, "note")
    }

    sealed class ReplaceLineFailure(code: String, kind: FailureKind, field: String? = null, meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, field, meta = meta) {
        data class InvalidTransition(val transition: Order.InvalidTransition) :
            ReplaceLineFailure(transition.code, transition.kind, meta = transition.meta)

        /** The color put in doesn't fit the change: COLOR needs another color of the product, MODEL another product (400 on `variantId`). */
        data object VariantInvalid : ReplaceLineFailure("DESIRED_VARIANT_INVALID", FailureKind.INVALID, "variantId")

        /** The new product's colors in the order would be more than its limit per order (422). */
        data class LimitExceeded(val productId: ProductId) :
            ReplaceLineFailure("QUANTITY_EXCEEDS_LIMIT", FailureKind.BUSINESS_RULE, meta = mapOf("productIds" to listOf(productId.value)))
    }

    sealed class RequestReturnFailure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {
        /** Not delivered, a return open already, returned once already, or nothing returnable (422). */
        data class NotReturnable(val status: OrderStatus) :
            RequestReturnFailure("ORDER_NOT_RETURNABLE", FailureKind.BUSINESS_RULE, meta = mapOf("status" to status.name))

        /** Its time to return ended at [returnableUntil] (422). */
        data class WindowExpired(val returnableUntil: Instant) :
            RequestReturnFailure("RETURN_WINDOW_EXPIRED", FailureKind.BUSINESS_RULE, meta = mapOf("returnableUntil" to returnableUntil.toString()))

        /** Lines it doesn't have, not returnable, named twice or more than bought, each under its field (400). */
        data class ItemsInvalid(val problems: List<ResultFailure>) : RequestReturnFailure("VALIDATION_ERROR", FailureKind.INVALID, causes = problems)
    }

    sealed class PayBalanceFailure(code: String, kind: FailureKind, meta: Map<String, Any> = emptyMap()) : ResultFailure(code, kind, meta = meta) {
        /** Nothing is owed (paid already, or never was) (422). */
        data object NoBalanceDue : PayBalanceFailure("ORDER_ALREADY_PAID", FailureKind.BUSINESS_RULE)

        /** The gateway confirmed another amount than what is owed: a bug, never a payment. */
        data class AmountMismatch(val expected: Money, val paid: Money) :
            PayBalanceFailure("PAYMENT_AMOUNT_MISMATCH", FailureKind.CONFLICT, mapOf("expected" to expected.toman, "paid" to paid.toman))
    }
}
