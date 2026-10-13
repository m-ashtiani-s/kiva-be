package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.PlacedOrder
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.port.RequestHash
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.EventStoreTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ReservationGroupTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «رزرو ۴ روزه» (roadmap section 13, step 19) over the real stack on kiva_test: a paid order held for 4 days, the next
 * orders to the same address joining it with no shipping cost, and the job letting it go. Monday 2026-10-12, 11:30 in
 * Tehran (۲۰ مهر).
 */
class WebV1ReservationsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val fourDays = Duration.ofDays(4)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored() // their reservation groups go with them
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val nilaBlack by lazy { purchase.variantOf("nila-crossbody-bag", "black") }
    private val mahtabWhite by lazy { purchase.variantOf("mahtab-clutch", "white") }

    // ============ helpers ============

    private fun fields(line: String = "خیابان ولیعصر، کوچه نسترن") =
        AddressFields(null, 8, 394, line, null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null)

    /** A saved address of [userId]'s book; the first one is their default. */
    private fun savedAddress(userId: UserId, line: String = "خیابان ولیعصر، کوچه نسترن"): Long =
        runBlocking { purchase.addAddress.execute(AddAddressUc.Cmd(userId, fields(line), setAsDefault = null)) }.getOrNull()!!.value

    private fun put(userId: UserId, variantId: Long) {
        runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), variantId, 1)) }.getOrNull() ?: error("not added")
    }

    private fun place(userId: UserId, addressId: Long, reserve: Boolean?, shippingMethod: String = "POST"): Result<PlacedOrder, PlaceOrderUc.Failure> {
        val key = UUID.randomUUID().toString()
        val command = PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = addressId, newAddress = null,
            newAddressAsDefault = null, saveNewAddress = false, messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
            shippingMethod = shippingMethod, reserve = reserve, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null,
            acceptTerms = true,
        )
        return runBlocking { purchase.placeOrder.execute(command) }
    }

    /** [userId] puts [variantId] in the cart and places an order to [addressId]; its payment session is opened. */
    private fun placed(userId: UserId, addressId: Long, variantId: Long = nilaOlive, reserve: Boolean? = null): PlacedOrder {
        put(userId, variantId)
        val order = place(userId, addressId, reserve).getOrNull() ?: error("not placed")
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, checkNotNull(order.paymentId))) }
        return order
    }

    /** The shopper pays [order] at the gateway and comes back. */
    private fun pay(order: PlacedOrder) {
        val authority = authorityOf(order)
        assertTrue(purchase.gateway.pay(authority))
        runBlocking { purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authority, "Status" to "OK"))) }
            .getOrNull() ?: error("callback refused")
    }

    private fun authorityOf(order: PlacedOrder): String = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq order.paymentId!!.value }.single()[PaymentTbl.authority]!!
    }

    /** A customer of [n] with one saved address, whose order with «رزرو ۴ روزه» was paid 2 minutes after placing. */
    private inner class Reserved(n: Int) {
        val userId = purchase.customer(phone(n))
        val addressId = savedAddress(userId)
        val order = placed(userId, addressId, reserve = true).also { clock.advance(Duration.ofMinutes(2)) }.also { pay(it) }
        val paidAt: Instant = clock.now
        val group: ReservationGroup get() = groupOf(userId)!!
    }

    private fun groupOf(userId: UserId): ReservationGroup? = transaction(purchase.db) { purchase.groups.findHolding(userId) }

    private fun groupById(id: ReservationGroupId): ReservationGroup = transaction(purchase.db) { purchase.groups.find(id)!! }

    private fun orderOf(userId: UserId, code: OrderCode) = transaction(purchase.db) { purchase.orderQm.findOfCustomer(userId, code)!! }

    private fun eventsOf(userId: UserId, code: OrderCode): Long = transaction(purchase.db) {
        val orderId = orderOf(userId, code).orderId.value.toString()
        EventStoreTbl.selectAll().where { (EventStoreTbl.aggregateName eq "ORDER") and (EventStoreTbl.aggregateId eq orderId) }.count()
    }

    private fun restock(variantId: Long, delta: Int) {
        others.stock(VariantId.constructFromPersisted(variantId)) {
            it.adjust(StockDelta.constructFromPersisted(delta), AdjustmentReason.RESTOCK, null, others.staff, clock.now)
        }
    }

    private fun moveTo(at: Instant) = clock.advance(Duration.between(clock.now, at))

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = purchase.app(block)

    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${purchase.auth.lastCodeSentTo(phone).value}"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun ApplicationTestBuilder.read(path: String, bearer: String): HttpResponse =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, "Bearer $bearer") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.option(method: String): JsonObject = this["shippingOptions"]!!.jsonArray.map { it.jsonObject }.single { it.text("method") == method }

    // ============ step 19 is done when ============

    @Test
    fun `reserved and paid - RESERVED in a new group SG-…, held 4 days from the payment, its page counting down`() = app {
        val reserved = Reserved(701)

        assertEquals(OrderStatus.RESERVED, orderOf(reserved.userId, reserved.order.orderCode).status)
        val group = reserved.group
        assertTrue(group.code.value.matches(Regex("SG-[0-9]+")), group.code.value)
        assertEquals(reserved.paidAt, group.startedOn)
        assertEquals(reserved.paidAt.plus(fourDays), group.expiresOn)
        assertEquals(group.id, orderOf(reserved.userId, reserved.order.orderCode).reservationGroupId)

        clock.advance(Duration.ofDays(1))
        val bearer = login(phone(701))
        val page = read("/me/reservation", bearer)

        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        val info = page.json()
        assertEquals(true, info["active"]!!.jsonPrimitive.boolean)
        assertEquals(reserved.order.orderCode.value, info.text("orderCode"))
        assertEquals(3 * 86_400L, info["remainingSeconds"]!!.jsonPrimitive.long)
        assertEquals(reserved.paidAt.plus(fourDays), Instant.parse(info.text("expiresAt")))
        assertEquals(listOf(reserved.order.orderCode.value), info["groupOrderCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("تا جمعه ۲۴ مهر هر خریدی کنی، با همین سفارش و بدون هزینه ارسال جدید فرستاده می‌شه.", info.text("message"))

        val card = read("/me/orders/${reserved.order.orderCode.value}", bearer).json()
        assertEquals("RESERVED", card.text("status"))
        assertEquals(reserved.order.orderCode.value, card["reservation"]!!.jsonObject.text("orderCode"))
        assertEquals(true, card["actions"]!!.jsonObject["canAddToReservation"]!!.jsonPrimitive.boolean)

        val result = read("/payments/${reserved.order.paymentId!!.value}", bearer).json()["order"]!!.jsonObject
        assertEquals(listOf("RESERVED", "true"), listOf(result.text("status"), result.text("reserved")))
        assertEquals(JsonNull, result["consolidatedInto"], "the order that started it")
    }

    @Test
    fun `nothing held - GET me-reservation is 204`() = app {
        purchase.customer(phone(702))

        assertEquals(HttpStatusCode.NoContent, read("/me/reservation", login(phone(702))).status)
    }

    @Test
    fun `a second order to the same address joins - cart and checkout say so, shipping 0 by the group's method only, RESERVED in the same group`() = app {
        val reserved = Reserved(703)
        val first = reserved.order.orderCode.value
        val expiresOn = reserved.group.expiresOn
        clock.advance(Duration.ofDays(1))
        put(reserved.userId, nilaBlack)
        val bearer = login(phone(703))

        val cart = read("/cart", bearer).json()
        val consolidation = cart["consolidation"]!!.jsonObject
        assertEquals(listOf(first, "POST", reserved.addressId.toString(), "true"), listOf("reservedOrderCode", "shippingMethod", "addressId", "shippingWaived").map { consolidation.text(it) })
        assertEquals("این خرید به سفارش رزروی $first اضافه می‌شه و هزینه ارسال نداره.", consolidation.text("message"))
        assertEquals("HAS_ACTIVE_RESERVATION", cart["reservation"]!!.jsonObject.text("unavailableReason"))

        val checkout = read("/checkout?addressId=${reserved.addressId}", bearer).json()
        assertEquals(first, checkout["consolidation"]!!.jsonObject.text("reservedOrderCode"))
        val post = checkout.option("POST")
        assertEquals(listOf("0", "true", "RESERVATION_CONSOLIDATION"), listOf(post.text("cost"), post.text("isFree"), post.text("freeReason")))
        val tipax = checkout.option("TIPAX")
        assertEquals(listOf("false", "سفارش رزروی با پست معمولی ارسال می‌شه"), listOf(tipax.text("available"), tipax.text("unavailableReason")))

        assertEquals("SHIPPING_METHOD_UNAVAILABLE", place(reserved.userId, reserved.addressId, reserve = null, shippingMethod = "TIPAX").failureOrNull()?.code)
        val second = place(reserved.userId, reserved.addressId, reserve = true).getOrNull()!! // its own «رزرو» is ignored
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(reserved.userId, second.paymentId!!)) }
        assertEquals(1_450_000, orderOf(reserved.userId, second.orderCode).totals.payable.toman, "no shipping")
        pay(second)

        val joined = orderOf(reserved.userId, second.orderCode)
        assertEquals(OrderStatus.RESERVED, joined.status)
        assertEquals(reserved.group.id, joined.reservationGroupId)
        assertEquals(expiresOn, reserved.group.expiresOn, "joining doesn't extend it")
        val info = read("/me/reservation", bearer).json()
        assertEquals(listOf(first, second.orderCode.value), info["groupOrderCodes"]!!.jsonArray.map { it.jsonPrimitive.content })
        val result = read("/payments/${second.paymentId!!.value}", bearer).json()["order"]!!.jsonObject
        assertEquals(first, result.text("consolidatedInto"))
    }

    @Test
    fun `another address - shipping as usual, and paid it is PROCESSING, outside the group`() = app {
        val reserved = Reserved(704)
        val office = savedAddress(reserved.userId, line = "خیابان شریعتی، کوچه یاس")
        clock.advance(Duration.ofHours(2))
        put(reserved.userId, nilaBlack)
        val bearer = login(phone(704))

        val checkout = read("/checkout?addressId=$office", bearer).json()
        assertEquals(JsonNull, checkout["consolidation"])
        assertTrue(checkout.option("POST")["cost"]!!.jsonPrimitive.long > 0)

        val other = place(reserved.userId, office, reserve = null).getOrNull()!!
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(reserved.userId, other.paymentId!!)) }
        assertEquals(1_525_000, orderOf(reserved.userId, other.orderCode).totals.payable.toman)
        pay(other)

        assertEquals(OrderStatus.PROCESSING, orderOf(reserved.userId, other.orderCode).status)
        assertNull(orderOf(reserved.userId, other.orderCode).reservationGroupId)
    }

    @Test
    fun `the group's address edited since - no joining, shipping as usual`() = app {
        val reserved = Reserved(705)
        runBlocking {
            purchase.addressUseCases.updateAddress.execute(
                UpdateAddressUc.Cmd(reserved.userId, reserved.addressId.toString(), fields(line = "خیابان ولیعصر، کوچه نسترن، پلاک ۱۴"), setAsDefault = null),
            )
        }.getOrNull()!!
        put(reserved.userId, nilaBlack)
        val bearer = login(phone(705))

        assertEquals(JsonNull, read("/cart", bearer).json()["consolidation"])
        assertEquals(JsonNull, read("/checkout?addressId=${reserved.addressId}", bearer).json()["consolidation"])
        val order = place(reserved.userId, reserved.addressId, reserve = null).getOrNull()!!
        assertNull(orderOf(reserved.userId, order.orderCode).reservationGroupId)
        assertEquals(1_525_000, orderOf(reserved.userId, order.orderCode).totals.payable.toman)
    }

    @Test
    fun `«کلاچ مهتاب» can't be reserved - ITEM_NOT_RESERVABLE, and while a group holds a new «رزرو» elsewhere is HAS_ACTIVE_RESERVATION`() {
        val userId = purchase.customer(phone(706))
        val home = savedAddress(userId)
        put(userId, mahtabWhite)

        val clutch = place(userId, home, reserve = true).failureOrNull()
        assertEquals("RESERVATION_UNAVAILABLE", clutch?.code)
        assertEquals(mapOf("reason" to "ITEM_NOT_RESERVABLE"), clutch?.meta)

        val reserved = Reserved(707)
        val office = savedAddress(reserved.userId, line = "خیابان شریعتی، کوچه یاس")
        put(reserved.userId, nilaBlack)
        val again = place(reserved.userId, office, reserve = true).failureOrNull()
        assertEquals(mapOf("reason" to "HAS_ACTIVE_RESERVATION"), again?.meta)
    }

    @Test
    fun `4 days on, the job lets go - every RESERVED order of the group is PROCESSING, the group RELEASED - running again changes nothing`() = app {
        val reserved = Reserved(708)
        clock.advance(Duration.ofDays(1))
        val second = placed(reserved.userId, reserved.addressId, nilaBlack).also { pay(it) }
        val codes = listOf(reserved.order.orderCode, second.orderCode)
        val group = reserved.group

        moveTo(group.expiresOn.minusSeconds(1))
        runBlocking { purchase.jobs.releaseReservations() }
        assertEquals(listOf(OrderStatus.RESERVED, OrderStatus.RESERVED), codes.map { orderOf(reserved.userId, it).status })

        moveTo(group.expiresOn.plusSeconds(30))
        runBlocking { purchase.jobs.releaseReservations() }
        assertEquals(listOf(OrderStatus.PROCESSING, OrderStatus.PROCESSING), codes.map { orderOf(reserved.userId, it).status })
        val released = groupById(group.id)
        assertEquals(ReservationGroupStatus.RELEASED, released.status)
        assertEquals(clock.now, released.releasedOn)
        assertNull(groupOf(reserved.userId))

        val events = codes.map { eventsOf(reserved.userId, it) }
        clock.advance(Duration.ofMinutes(1))
        runBlocking { purchase.jobs.releaseReservations() }
        assertEquals(events, codes.map { eventsOf(reserved.userId, it) })
        assertEquals(released.releasedOn, groupById(group.id).releasedOn)
        assertEquals(HttpStatusCode.NoContent, read("/me/reservation", login(phone(708))).status)
    }

    @Test
    fun `the group's time runs out between placing and paying - shipping stays free, and paid the order is PROCESSING, shipping with the group`() {
        val reserved = Reserved(709)
        val group = reserved.group
        moveTo(group.expiresOn.minusSeconds(60))
        val late = placed(reserved.userId, reserved.addressId, nilaBlack)
        assertEquals(1_450_000, orderOf(reserved.userId, late.orderCode).totals.payable.toman)

        moveTo(group.expiresOn.plusSeconds(60)) // the job hasn't run yet: the group is still marked HOLDING
        pay(late)

        val order = orderOf(reserved.userId, late.orderCode)
        assertEquals(OrderStatus.PROCESSING, order.status)
        assertEquals(group.id, order.reservationGroupId)
        assertEquals(1_450_000, order.totals.payable.toman)

        runBlocking { purchase.jobs.releaseReservations() }
        assertEquals(OrderStatus.PROCESSING, orderOf(reserved.userId, reserved.order.orderCode).status)
        assertEquals(ReservationGroupStatus.RELEASED, groupById(group.id).status)
    }

    @Test
    fun `two «رزرو» orders paid at the same moment, 5 times - one group, both held in it`() {
        restock(nilaOlive, 20)
        repeat(5) { round ->
            val userId = purchase.customer(phone(720 + round))
            val home = savedAddress(userId)
            val orders = listOf(placed(userId, home, reserve = true), placed(userId, home, reserve = true))
            val authorities = orders.map { authorityOf(it) }
            authorities.forEach { assertTrue(purchase.gateway.pay(it)) }

            atOnce(2) { purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authorities[it], "Status" to "OK"))) }

            val group = assertNotNull(groupOf(userId), "round $round")
            val held = orders.map { orderOf(userId, it.orderCode) }
            assertEquals(listOf(OrderStatus.RESERVED, OrderStatus.RESERVED), held.map { it.status }, "round $round")
            assertEquals(listOf(group.id, group.id), held.map { it.reservationGroupId }, "round $round")
            val groups = transaction(purchase.db) { ReservationGroupTbl.selectAll().where { ReservationGroupTbl.userId eq userId.value }.count() }
            assertEquals(1L, groups, "round $round")
        }
    }

    @Test
    fun `two «رزرو» orders to two addresses paid at the same moment, 5 times - one group, the other order ships now`() {
        restock(nilaOlive, 20)
        repeat(5) { round ->
            val userId = purchase.customer(phone(740 + round))
            val home = savedAddress(userId)
            val office = savedAddress(userId, line = "خیابان شریعتی، کوچه یاس")
            val orders = listOf(placed(userId, home, reserve = true), placed(userId, office, reserve = true))
            val authorities = orders.map { authorityOf(it) }
            authorities.forEach { assertTrue(purchase.gateway.pay(it)) }

            atOnce(2) { purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authorities[it], "Status" to "OK"))) }

            val group = assertNotNull(groupOf(userId), "round $round")
            val (held, shipped) = orders.map { orderOf(userId, it.orderCode) }.partition { it.status == OrderStatus.RESERVED }
            assertEquals(1, held.size, "round $round")
            assertEquals(OrderStatus.PROCESSING, shipped.single().status, "round $round")
            assertEquals(group.id, held.single().reservationGroupId)
            assertEquals(held.single().orderId, group.rootOrderId)
            assertNull(shipped.single().reservationGroupId)
        }
    }

    /** Runs [work] on [n] threads released together, so the database really sees the calls at the same moment. */
    private fun <T> atOnce(n: Int, work: suspend (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val calls = List(n) { index -> pool.submit<T> { start.await(); runBlocking { work(index) } } }
            start.countDown()
            calls.map { it.get(120, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }
}
