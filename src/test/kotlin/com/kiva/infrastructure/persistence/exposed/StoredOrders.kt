package com.kiva.infrastructure.persistence.exposed

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
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.PricedLine
import com.kiva.domain.aggregate.order.model.policy.OrderCodePolicy
import com.kiva.domain.aggregate.order.model.policy.OrderPricing
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.CustomerNote
import com.kiva.domain.aggregate.order.model.valueobject.GiftNote
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.order.model.valueobject.OrderItemSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.order.model.valueobject.PreShipmentMessenger
import com.kiva.domain.aggregate.product.entity.Product
import com.kiva.domain.aggregate.product.model.valueobject.MaxPerOrder
import com.kiva.domain.aggregate.product.model.valueobject.PriceTag
import com.kiva.domain.aggregate.product.model.valueobject.ProductName
import com.kiva.domain.aggregate.product.model.valueobject.Sku
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.time.Instant
import kotlin.test.fail

/**
 * Orders placed and changed on kiva_test through the real repository, so their events and read model are written
 * exactly as in the app; each call is its own transaction, like one request. Their bags are real products of the read
 * model ([catalog]), because a payment counts them as sold. [deleteAll] removes everything, and belongs in an @AfterEach.
 */
class StoredOrders(val catalog: StoredCatalog = StoredCatalog()) {

    private val db = TestDatabase.database
    val repository = OrderRepositoryImpl()
    val ids = OrderIdGeneratorImpl()
    private val orderIds = mutableListOf<Long>()

    val sara: UserId = UserId.constructFromPersisted(7)
    val now: Instant = Instant.parse("2026-10-12T08:00:00Z")

    private val phone = IranianMobileNumber.constructFromPersisted("09121234567")
    val address: AddressSnapshot = AddressSnapshot.constructFromPersisted(
        AddressId.constructFromPersisted(42), "تهران", "تهران", AddressLine.constructFromPersisted("خیابان ولیعصر، کوچه نسترن"),
        Plaque.constructFromPersisted("12"), UnitNumber.constructFromPersisted("4"), PostalCode.constructFromPersisted("1965843117"),
        RecipientName.constructFromPersisted("سارا محمدی"), phone,
    )
    val messenger: PreShipmentMessenger = PreShipmentMessenger.constructFromPersisted(MessengerChannel.TELEGRAM, phone, "@sara_m", null)

    /** A bag in the shop's read model with a caramel and a black color. */
    fun newBag(): Product = catalog.newProduct(listOf(NewColor("caramel"), NewColor("black")))

    /** [quantity] of the [color]-th color of [bag], at [price] (and [compareAt]) each. */
    fun line(lineId: Int, bag: Product, color: Int = 0, quantity: Int = 1, price: Long = 1_450_000, compareAt: Long? = 1_750_000): OrderLine {
        val variant = bag.variants[color]
        return OrderLine.constructFromPersisted(
            lineId, bag.id, variant.id,
            OrderItemSnapshot(
                ProductName.constructFromPersisted("کیف دستی آیدا"), Slug.constructFromPersisted(bag.slug.value), Sku.constructFromPersisted(variant.sku.value),
                OrderItemSnapshot.Color(ColorKey.constructFromPersisted(variant.colorKey.value), "کاراملی", "#C68B59"), null,
            ),
            Quantity.constructFromPersisted(quantity),
            PriceTag.constructFromPersisted(Money.constructFromPersisted(price), compareAt?.let { Money.constructFromPersisted(it) }),
            reservable = true, returnable = true,
        )
    }

    /** Places an order of [lines] by post, priced by OrderPricing with the shop's real settings, and saves it: one request. */
    fun place(
        lines: List<OrderLine>,
        userId: UserId = sara,
        at: Instant = now,
        totals: OrderTotals? = null,
        customerNote: CustomerNote? = null,
        gift: GiftNote? = null,
    ): Order = transaction(db) {
        val id = ids.nextOrderId()
        orderIds += id.value
        val priced = totals ?: OrderPricing.quote(
            lines.map { PricedLine(it.priceTag, it.quantity, purchasable = true) }, ShopSettingsRepositoryImpl().get(), ShippingMethodCode.POST, at,
        ).totals
        val order = Order.place(
            id, OrderCodePolicy.codeOf(id), userId, lines, lines.associate { it.productId to MaxPerOrder.DEFAULT }, address, messenger,
            ShippingMethodCode.POST, priced, reserveRequested = false, customerNote, gift, Duration.ofMinutes(15), userId, at,
        ).getOrNull() ?: fail("the order was refused")
        repository.save(order)
        order
    }

    /** Reads the order, changes it and saves the change: one request (a callback, the expiry job). */
    fun change(id: OrderId, change: (Order) -> Unit): Order = transaction(db) {
        val order = repository.get(id) ?: fail("Order ${id.value} not stored")
        change(order)
        repository.save(order)
        order
    }

    fun read(id: OrderId): Order? = transaction(db) { repository.get(id) }

    fun soldCountOf(bag: Product): Int = transaction(db) {
        ProductQmTbl.select(ProductQmTbl.soldCount).where { ProductQmTbl.productId eq bag.id.value }.single()[ProductQmTbl.soldCount]
    }

    /** Remembers an order id a test took from the sequence itself, so [deleteAll] removes its rows too. */
    fun remember(id: OrderId) {
        orderIds += id.value
    }

    fun deleteAll() {
        transaction(db) {
            EventStoreTbl.deleteWhere { (aggregateName eq "ORDER") and (aggregateId inList orderIds.map { it.toString() }) }
            OrderQmTbl.deleteWhere { orderId inList orderIds }
        }
        orderIds.clear()
        catalog.deleteAll()
    }
}
