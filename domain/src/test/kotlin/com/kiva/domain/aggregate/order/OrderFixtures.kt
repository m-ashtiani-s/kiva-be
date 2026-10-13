package com.kiva.domain.aggregate.order

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
import com.kiva.domain.aggregate.cart.CartFixtures
import com.kiva.domain.aggregate.cart.model.PurchaseOffer
import com.kiva.domain.aggregate.color.model.valueobject.ColorKey
import com.kiva.domain.aggregate.discount.model.CodeDiscount
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.model.OrderPlacement
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.order.repository.OrderIdGenerator
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import java.time.Instant

/**
 * Ready-made values for the order tests: Sara's order of the prototype's bags (roadmap section 7.5), placed on
 * Monday 2026-10-12 at 11:30 in Tehran ([now]).
 */
object OrderFixtures {

    val now: Instant = Instant.parse("2026-10-12T08:00:00Z")

    /** [minutes] after [now]. */
    fun at(minutes: Long): Instant = now.plusSeconds(minutes * 60)

    val sara: UserId = UserId.constructFromPersisted(7)

    const val KV1015_BROWN = 1501L

    /** کیف دستی آیدا، کاراملی: 2,790,000 instead of 3,290,000; 3 left. */
    val aidaCaramel: PurchaseOffer = CartFixtures.aida(CartFixtures.AIDA_CARAMEL)

    /** KV-1015، قهوه‌ای: 690,000 instead of 790,000; 10 left. Not returnable, to see it copied. */
    val kv1015Brown: PurchaseOffer = CartFixtures.offer(KV1015_BROWN, productId = 15, price = 690_000, compareAt = 790_000, returnable = false)

    /** کراس‌بادی نیلا، مشکی: 1,450,000 instead of 1,750,000; 10 left. */
    val nilaBlack: PurchaseOffer = CartFixtures.nila(CartFixtures.NILA_BLACK)

    fun item(sku: String = "KV-1003-CARAMEL", colorKey: String = "caramel", colorName: String = "کاراملی"): OrderItemSnapshot = OrderItemSnapshot(
        name = ProductName.constructFromPersisted("کیف دستی آیدا"),
        slug = Slug.constructFromPersisted("aida-handbag"),
        sku = Sku.constructFromPersisted(sku),
        color = OrderItemSnapshot.Color(ColorKey.constructFromPersisted(colorKey), colorName, "#C68B59"),
        imageUrl = null,
    )

    val saraPhone: IranianMobileNumber = IranianMobileNumber.constructFromPersisted("09121234567")

    val address: AddressSnapshot = AddressSnapshot.constructFromPersisted(
        addressId = AddressId.constructFromPersisted(42),
        provinceName = "تهران",
        cityName = "تهران",
        line = AddressLine.constructFromPersisted("خیابان ولیعصر، بالاتر از پارک ساعی، کوچه نسترن"),
        plaque = Plaque.constructFromPersisted("12"),
        unit = UnitNumber.constructFromPersisted("4"),
        postalCode = PostalCode.constructFromPersisted("1965843117"),
        recipientName = RecipientName.constructFromPersisted("سارا محمدی"),
        recipientPhone = saraPhone,
    )

    val messenger: PreShipmentMessenger = PreShipmentMessenger.constructFromPersisted(MessengerChannel.TELEGRAM, saraPhone, "@sara_m", null)

    /** A cart line of [offer]; the cart line ids are ci_0000000001, ci_0000000002, … */
    fun line(cartItem: Int, offer: PurchaseOffer, quantity: Int = 1, item: OrderItemSnapshot = item()): OrderPlacement.Line =
        OrderPlacement.Line(CartFixtures.itemId(cartItem), Quantity.constructFromPersisted(quantity), offer, item)

    /** Sara's order as she places it: the first golden cart, by post, everything right. Each test changes what it is about. */
    fun placement(
        lines: List<OrderPlacement.Line> = listOf(line(1, aidaCaramel), line(2, kv1015Brown, item = item("KV-1015-BROWN", "brown", "قهوه‌ای"))),
        shippingMethod: ShippingMethodCode = ShippingMethodCode.POST,
        gatewayAvailable: Boolean = true,
        acceptTerms: Boolean = true,
        reserve: Boolean = false,
        customerNote: CustomerNote? = null,
        gift: GiftNote? = null,
        expectedPayable: Long? = null,
        unpaidOrdersHoldingUntil: List<Instant> = emptyList(),
        settings: ShopSettings = PricingFixtures.shopSettings(),
        codeDiscount: CodeDiscount? = null,
    ): OrderPlacement = OrderPlacement(
        userId = sara,
        lines = lines,
        address = address,
        messenger = messenger,
        shippingMethod = shippingMethod,
        gatewayAvailable = gatewayAvailable,
        acceptTerms = acceptTerms,
        reserve = reserve,
        customerNote = customerNote,
        gift = gift,
        expectedPayable = expectedPayable?.let { Money.constructFromPersisted(it) },
        unpaidOrdersHoldingUntil = unpaidOrdersHoldingUntil,
        settings = settings,
        codeDiscount = codeDiscount,
    )

    /** Hands out 1, 2, 3, … and remembers how many it did. */
    class SequentialOrderIds : OrderIdGenerator {
        var handedOut = 0
            private set

        override fun nextOrderId(): OrderId = OrderId.constructFromPersisted((++handedOut).toLong())
    }

    /** Sara's golden order, placed: 3,480,000, shipping free by post. */
    fun placedOrder(placement: OrderPlacement = placement(), at: Instant = now): Order =
        OrderDomainService(SequentialOrderIds()).placeOrder(placement, at).getOrNull() ?: error("the golden order was refused")
}
