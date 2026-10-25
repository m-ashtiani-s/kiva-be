package com.kiva.infrastructure.persistence.event

import com.kiva.common.event.valueobject.EventId
import com.kiva.common.event.valueobject.EventSequence
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.Quantity
import com.kiva.common.valueobject.Slug
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.Plaque
import com.kiva.domain.aggregate.address.model.valueobject.PostalCode
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.address.model.valueobject.UnitNumber
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import com.kiva.domain.aggregate.order.entity.OrderLine
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
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosApproved
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosAttached
import com.kiva.domain.aggregate.order.entity.event.OrderPhotosSent
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderRefunded
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.entity.event.OrderPersonalDataErased
import com.kiva.domain.aggregate.order.entity.event.OrderReturnEnded
import com.kiva.domain.aggregate.order.entity.event.OrderReturnReceived
import com.kiva.domain.aggregate.order.entity.event.OrderReturnRequested
import com.kiva.domain.aggregate.order.entity.event.OrderShipmentCorrected
import com.kiva.domain.aggregate.order.entity.event.OrderShipped
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CancelReason
import com.kiva.domain.aggregate.order.model.valueobject.CancelledBy
import com.kiva.domain.aggregate.order.model.valueobject.ChangeType
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.ErasureReason
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderPhoto
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PhotoMessage
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.order.model.valueobject.StatusNote
import com.kiva.domain.aggregate.order.model.valueobject.TrackingCode
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.MediaView
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.refund.model.valueobject.RefundId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnId
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnOutcome
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * One event of every kind of order event, every field filled in (Sara's golden order: Aida caramel and KV-1015 by post,
 * 3,480,000), plus the ones with every optional part left empty.
 */
object OrderEventSamples {

    private val order = OrderId.constructFromPersisted(42)
    private val sara = UserId.constructFromPersisted(7)

    // Down to the nanosecond on purpose: the stored event must keep the exact moment.
    private val at = Instant.parse("2026-10-12T08:00:00.123456789Z")

    private fun eventId(n: Int) = EventId.constructFromPersisted("5d0f3a2c-8b1e-4c7d-9a6f-%012d".format(n))
    private fun sequence(n: Long) = EventSequence.constructFromPersisted(n)
    private fun toman(amount: Long) = Money.constructFromPersisted(amount)

    private fun line(
        lineId: Int,
        productId: Long,
        variantId: Long,
        name: String,
        slug: String,
        sku: String,
        color: OrderItemSnapshot.Color,
        imageUrl: String?,
        priceTag: PriceTag,
        reservable: Boolean,
        returnable: Boolean,
    ) = OrderLine.constructFromPersisted(
        lineId, ProductId.constructFromPersisted(productId), VariantId.constructFromPersisted(variantId),
        OrderItemSnapshot(ProductName.constructFromPersisted(name), Slug.constructFromPersisted(slug), Sku.constructFromPersisted(sku), color, imageUrl),
        Quantity.constructFromPersisted(1), priceTag, reservable, returnable,
    )

    private val caramel = line(
        1, 3, 301, "کیف دستی آیدا", "aida-handbag", "KV-1003-CARAMEL",
        OrderItemSnapshot.Color(ColorKey.constructFromPersisted("caramel"), "کاراملی", "#C68B59"), "https://cdn.kiva.ir/p/aida/caramel-1.jpg",
        PriceTag.constructFromPersisted(toman(2_790_000), toman(3_290_000)), reservable = true, returnable = true,
    )
    private val brown = line(
        2, 15, 1501, "کیف پول KV-1015", "kv-1015-wallet", "KV-1015-BROWN",
        OrderItemSnapshot.Color(ColorKey.constructFromPersisted("brown"), "شکلاتی", "#7B4A2D"), null,
        PriceTag.constructFromPersisted(toman(690_000), toman(790_000)), reservable = false, returnable = false,
    )

    private val phone = IranianMobileNumber.constructFromPersisted("09121234567")

    /** The reservation group the golden order joined and is held in. */
    private val sg1001 = ReservationGroupId.constructFromPersisted(1001)

    private val goldenTotals = OrderTotals.constructFromPersisted(
        itemsCount = 2, linesCount = 2, itemsCompareAtTotal = toman(4_080_000), productDiscount = toman(600_000), subtotal = toman(3_480_000),
        codeDiscount = Money.ZERO, shippingCost = Money.ZERO, shippingIsFree = true, shippingFreeReason = ShippingFreeReason.THRESHOLD,
        payable = toman(3_480_000),
    )

    val orderPlaced = OrderPlaced(
        orderId = order,
        sequence = sequence(1),
        occurredOn = at,
        occurredByUserId = sara,
        code = OrderCode.constructFromPersisted("KV-218340"),
        userId = sara,
        lines = listOf(caramel, brown),
        address = AddressSnapshot.constructFromPersisted(
            AddressId.constructFromPersisted(42), "تهران", "تهران", AddressLine.constructFromPersisted("خیابان ولیعصر، کوچه نسترن"),
            Plaque.constructFromPersisted("12"), UnitNumber.constructFromPersisted("4"), PostalCode.constructFromPersisted("1965843117"),
            RecipientName.constructFromPersisted("سارا محمدی"), phone,
        ),
        messenger = PreShipmentMessenger.constructFromPersisted(MessengerChannel.TELEGRAM, phone, "@sara_m", "از داخل کیف هم عکس بگیرید"),
        shippingMethod = ShippingMethodCode.POST,
        totals = goldenTotals,
        reserveRequested = false,
        holdUntil = Instant.parse("2026-10-12T08:15:00.123456789Z"),
        customerNote = CustomerNote.constructFromPersisted("لطفاً قبل از ارسال زنگ بزنید"),
        gift = GiftNote.constructFromPersisted("تولدت مبارک!", hidePrices = true),
        reservationGroupId = sg1001,
        id = eventId(1),
    )

    val orderPaymentFailed = OrderPaymentFailed(
        order, sequence(2), at, UserId.SYSTEM, PaymentPublicId.constructFromPersisted("pay_0000000000A1"), PaymentFailureReason.USER_CANCELLED, eventId(2),
    )

    val orderPaid = OrderPaid(
        order, sequence(3), at, UserId.SYSTEM, PaymentPublicId.constructFromPersisted("pay_0000000000B2"), toman(3_480_000),
        heldForReservation = true, reservationGroupId = sg1001, id = eventId(3),
    )

    val orderExpired = OrderExpired(order, sequence(4), at, UserId.SYSTEM, eventId(4))

    val orderReleasedFromReservation = OrderReleasedFromReservation(order, sequence(4), at, UserId.SYSTEM, eventId(5))

    private val operator = UserId.constructFromPersisted(900)

    val orderPhotosAttached = OrderPhotosAttached(
        order, sequence(5), at, operator,
        listOf(
            OrderPhoto(MediaId.constructFromPersisted("med_0000000000000000000001"), lineId = 1, view = MediaView.FRONT, sortOrder = 0),
            OrderPhoto(MediaId.constructFromPersisted("med_0000000000000000000002"), lineId = null, view = null, sortOrder = 1),
        ),
        eventId(6),
    )

    val orderPhotosSent = OrderPhotosSent(
        order, sequence(6), at, operator, batchNo = 1, channel = MessengerChannel.BALE, manual = true,
        message = PhotoMessage.constructFromPersisted("این کیف خودته"), feedbackDeadline = Instant.parse("2026-10-13T08:00:00.123456789Z"), id = eventId(7),
    )

    val orderPhotosApproved = OrderPhotosApproved(order, sequence(7), at, UserId.SYSTEM, automatic = true, id = eventId(8))

    val orderShipped = OrderShipped(
        order, sequence(8), at, operator, ShippingMethodCode.POST, TrackingCode.constructFromPersisted("183920674512039845612307"),
        shippedOn = Instant.parse("2026-10-13T06:30:00Z"), showInDailyList = true, reservationGroupId = sg1001, id = eventId(9),
    )

    val orderShipmentCorrected = OrderShipmentCorrected(
        order, sequence(9), at, operator, TrackingCode.constructFromPersisted("183920674512039845612308"), Instant.parse("2026-10-13T06:00:00Z"),
        showInDailyList = false, id = eventId(10),
    )

    val orderDelivered = OrderDelivered(
        order, sequence(10), at, operator, returnableUntil = Instant.parse("2026-10-19T08:00:00.123456789Z"),
        note = StatusNote.constructFromPersisted("با پیک رسید"), id = eventId(14),
    )

    val orderCancelled = OrderCancelled(
        order, sequence(5), at, sara, CancelledBy.CUSTOMER, CancelReason.FOUND_CHEAPER, StatusNote.constructFromPersisted("ارزون‌ترش رو پیدا کردم"),
        id = eventId(15),
    )

    val orderRefunded = OrderRefunded(order, sequence(6), at, operator, RefundId.constructFromPersisted(31), toman(3_480_000), id = eventId(16))

    /** Sara asks for her caramel Aida in black. */
    val orderChangeRequested = OrderChangeRequested(
        order, sequence(8), at, sara, ChangeType.COLOR, lineId = 1, desiredVariantId = VariantId.constructFromPersisted(302),
        note = StatusNote.constructFromPersisted("می‌شه رنگ مشکی‌اش رو بفرستید؟"), id = eventId(21),
    )

    val orderChangeRejected = OrderChangeRejected(order, sequence(9), at, operator, StatusNote.constructFromPersisted("مشکی تموم شده"), id = eventId(22))

    /** The caramel Aida (2,790,000) swapped for the black one at 2,990,000 today. */
    val orderLineReplaced = OrderLineReplaced(
        order, sequence(9), at, operator, lineId = 1,
        line = line(
            1, 3, 302, "کیف دستی آیدا", "aida-handbag", "KV-1003-BLACK",
            OrderItemSnapshot.Color(ColorKey.constructFromPersisted("black"), "مشکی", "#1B1B1B"), "2026/10/med_0000000000000000000009/480.webp",
            PriceTag.constructFromPersisted(toman(2_990_000), toman(3_290_000)), reservable = true, returnable = true,
        ),
        priceDifference = 200_000, id = eventId(23),
    )

    val orderLineRemoved = OrderLineRemoved(order, sequence(9), at, operator, lineId = 2, paidShare = toman(621_000), id = eventId(24))

    val orderChangeApplied = OrderChangeApplied(
        order, sequence(10), at, operator, ChangeType.COLOR, variantId = VariantId.constructFromPersisted(302),
        note = StatusNote.constructFromPersisted("مشکیش رو گذاشتیم"), balanceDueAfter = toman(200_000), id = eventId(25),
    )

    val orderBalancePaid = OrderBalancePaid(order, sequence(11), at, sara, PaymentPublicId.constructFromPersisted("pay_0000000000C3"), toman(200_000), id = eventId(26))

    private val rt1012 = ReturnId.constructFromPersisted(12)

    /** Sara sends her caramel Aida back (RT-1012); it arrives; its money goes back. */
    val orderReturnRequested = OrderReturnRequested(order, sequence(11), at, sara, rt1012, id = eventId(30))

    val orderReturnReceived = OrderReturnReceived(order, sequence(12), at, operator, rt1012, id = eventId(31))

    val orderReturnEnded = OrderReturnEnded(order, sequence(13), at, operator, rt1012, ReturnOutcome.REFUNDED, id = eventId(32))

    /** Sara deletes her account: the order stays, without her name, phone or address (phase 14). */
    val orderPersonalDataErased = OrderPersonalDataErased(order, sequence(14), at, sara, ErasureReason.ACCOUNT_DELETED, id = eventId(40))

    val onePerKind: List<OrderDomainEvent> = listOf(
        orderPlaced, orderPaymentFailed, orderPaid, orderExpired, orderReleasedFromReservation, orderPhotosAttached, orderPhotosSent, orderPhotosApproved,
        orderShipped, orderShipmentCorrected, orderDelivered, orderCancelled, orderRefunded, orderChangeRequested, orderChangeRejected, orderLineReplaced,
        orderLineRemoved, orderChangeApplied, orderBalancePaid, orderReturnRequested, orderReturnReceived, orderReturnEnded, orderPersonalDataErased,
    )

    /** A new address that wasn't saved, without plaque or unit; no messenger handle or note; no customer note or gift; nothing to pay. */
    val nothingOptional: List<OrderDomainEvent> = listOf(
        OrderPlaced(
            orderId = order,
            sequence = sequence(1),
            occurredOn = at,
            occurredByUserId = sara,
            code = OrderCode.constructFromPersisted("KV-218340"),
            userId = sara,
            lines = listOf(
                line(
                    1, 9, 901, "نمونه‌ی رایگان", "free-sample", "KV-1009-LILAC",
                    OrderItemSnapshot.Color(ColorKey.constructFromPersisted("lilac"), "یاسی", "#C8A2C8"), null,
                    PriceTag.constructFromPersisted(Money.ZERO, null), reservable = true, returnable = true,
                ),
            ),
            address = AddressSnapshot.constructFromPersisted(
                null, "تهران", "تهران", AddressLine.constructFromPersisted("خیابان ولیعصر، کوچه نسترن"), null, null,
                PostalCode.constructFromPersisted("1965843117"), RecipientName.constructFromPersisted("سارا محمدی"), phone,
            ),
            messenger = PreShipmentMessenger.constructFromPersisted(MessengerChannel.BALE, phone, null, null),
            shippingMethod = ShippingMethodCode.POST,
            totals = OrderTotals.constructFromPersisted(1, 1, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, true, ShippingFreeReason.THRESHOLD, Money.ZERO),
            reserveRequested = true,
            holdUntil = Instant.parse("2026-10-12T08:15:00.123456789Z"),
            customerNote = null,
            gift = null,
            id = eventId(11),
        ),
        OrderPaid(order, sequence(2), at, sara, paymentId = null, amount = Money.ZERO, heldForReservation = false, id = eventId(12)),
        OrderPlaced(
            orderId = order,
            sequence = sequence(1),
            occurredOn = at,
            occurredByUserId = sara,
            code = OrderCode.constructFromPersisted("KV-218340"),
            userId = sara,
            lines = listOf(caramel),
            address = orderPlaced.address,
            messenger = orderPlaced.messenger,
            shippingMethod = ShippingMethodCode.TIPAX,
            totals = OrderTotals.constructFromPersisted(1, 1, toman(3_290_000), toman(500_000), toman(2_790_000), Money.ZERO, toman(145_000), false, null, toman(2_935_000)),
            reserveRequested = false,
            holdUntil = orderPlaced.holdUntil,
            customerNote = null,
            gift = GiftNote.constructFromPersisted(null, hidePrices = false),
            id = eventId(13),
        ),
        // Photos sent without a message, an order shipped on its own, one delivered with nothing returnable, cancelled by staff without a note.
        OrderPhotosSent(order, sequence(6), at, operator, 2, MessengerChannel.TELEGRAM, true, null, Instant.parse("2026-10-13T08:00:00Z"), eventId(17)),
        OrderShipped(order, sequence(8), at, operator, ShippingMethodCode.TIPAX, TrackingCode.constructFromPersisted("TPX4821760"), at, false, null, eventId(18)),
        OrderDelivered(order, sequence(10), at, operator, returnableUntil = null, id = eventId(19)),
        OrderCancelled(order, sequence(5), at, operator, CancelledBy.STAFF, reason = null, note = null, id = eventId(20)),
        // Something else asked without a line or a color; a line taken out with nothing applied as money; OTHER applied with nothing owed.
        OrderChangeRequested(order, sequence(8), at, sara, ChangeType.OTHER, lineId = null, desiredVariantId = null, note = StatusNote.constructFromPersisted("بند بلندتر"), id = eventId(27)),
        OrderChangeRequested(order, sequence(8), at, sara, ChangeType.CANCEL_ITEM, lineId = 2, desiredVariantId = null, note = null, id = eventId(28)),
        OrderChangeApplied(order, sequence(10), at, operator, ChangeType.CANCEL_ITEM, variantId = null, note = null, balanceDueAfter = Money.ZERO, id = eventId(29)),
    )
}
