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
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.entity.event.OrderDomainEvent
import com.kiva.domain.aggregate.order.entity.event.OrderExpired
import com.kiva.domain.aggregate.order.entity.event.OrderPaid
import com.kiva.domain.aggregate.order.entity.event.OrderPaymentFailed
import com.kiva.domain.aggregate.order.entity.event.OrderPlaced
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.model.valueobject.ShippingFreeReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentFailureReason
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
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

    val onePerKind: List<OrderDomainEvent> = listOf(orderPlaced, orderPaymentFailed, orderPaid, orderExpired, orderReleasedFromReservation)

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
    )
}
