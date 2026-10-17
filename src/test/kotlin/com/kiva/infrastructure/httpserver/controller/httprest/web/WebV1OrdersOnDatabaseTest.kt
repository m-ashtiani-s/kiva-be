package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.dto.PlacedOrder
import com.kiva.application.port.PanelActor
import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.media.MediaAttachments
import com.kiva.application.usecase.product.ProductInput
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.entity.Cart
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentStatus
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.IDEMPOTENCY_KEY_HEADER
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.persistence.exposed.AuditTrailImpl
import com.kiva.infrastructure.persistence.exposed.MediaRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ProductRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ShopSettingsTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Placing an order over the real stack on kiva_test: the prototype's bags seeded as the dev seeder does it, the shop's
 * settings as V12 writes them, the pretend gateway as Zarinpal, customers with tokens from a real login, and, for the
 * races, the use case itself on many threads. Step 13 of the purchase roadmap is done when these pass.
 *
 * The pretend gateway refuses any call made inside a database transaction, so every order placed here is also the
 * architecture test of section 10.4: the gateway is called between transactions, never inside one.
 */
class WebV1OrdersOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = purchase.app(block)

    // ============ helpers ============

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val aidaBlack by lazy { purchase.variantOf("aida-handbag", "black") }
    private val hastiCream by lazy { purchase.variantOf("hasti-shoulder-bag", "cream") }
    private val termehLilac by lazy { purchase.variantOf("termeh-wallet", "lilac") }
    private val termehBlack by lazy { purchase.variantOf("termeh-wallet", "black") }
    private val rozhanCream by lazy { purchase.variantOf("rozhan-shopper-bag", "cream") }

    private val telegram = buildJsonObject {
        put("channel", "TELEGRAM")
        put("phone", "09121112233")
        put("username", "@sara_m")
    }

    private fun newAddress(postalCode: String = "1965843117") = buildJsonObject {
        put("provinceId", 8)
        put("cityId", 394)
        put("addressLine", "خیابان ولیعصر، کوچه نسترن")
        put("postalCode", postalCode)
        put("recipientName", "سارا محمدی")
        put("recipientPhone", "09123456789")
    }

    private fun body(
        addressId: Long? = null,
        newAddress: JsonObject? = null,
        saveNewAddress: Boolean? = null,
        messenger: JsonObject? = telegram,
        shippingMethod: String? = "POST",
        gateway: String? = "ZARINPAL",
        expectedPayable: Long? = null,
        acceptTerms: Boolean = true,
    ): String = buildJsonObject {
        addressId?.let { put("addressId", it) }
        newAddress?.let { put("newAddress", it) }
        saveNewAddress?.let { put("saveNewAddress", it) }
        messenger?.let { put("preShipmentMessenger", it) }
        shippingMethod?.let { put("shippingMethod", it) }
        gateway?.let { put("paymentGateway", it) }
        expectedPayable?.let { put("expectedPayable", it) }
        put("acceptTerms", acceptTerms)
    }.toString()

    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${purchase.auth.lastCodeSentTo(phone).value}"}""")
        }
        return response.json()["accessToken"]!!.jsonPrimitive.content
    }

    /** Adds a line a moment after the one before, as in real life. */
    private suspend fun ApplicationTestBuilder.add(bearer: String, variantId: Long, quantity: Int = 1) {
        clock.advance(Duration.ofSeconds(1))
        val response = client.post("$API_V1/cart/items") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":$quantity}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.addAddress(bearer: String): Long {
        clock.advance(Duration.ofSeconds(1))
        val response = client.post("$API_V1/me/addresses") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(newAddress().toString())
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().long("id")
    }

    private suspend fun ApplicationTestBuilder.place(bearer: String, json: String, key: String? = UUID.randomUUID().toString()): HttpResponse =
        client.post("$API_V1/orders") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            key?.let { header(IDEMPOTENCY_KEY_HEADER, it) }
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private suspend fun ApplicationTestBuilder.cartItems(bearer: String): List<JsonObject> =
        client.get("$API_V1/cart") { header(HttpHeaders.Authorization, "Bearer $bearer") }.json()["items"]!!.jsonArray.map { it.jsonObject }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String = json()["code"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]!!.jsonArray.map { "${it.jsonObject["field"]!!.jsonPrimitive.content} ${it.jsonObject["code"]!!.jsonPrimitive.content}" }

    private fun JsonObject.long(name: String): Long = this[name]!!.jsonPrimitive.long

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject

    private fun userIdOf(phone: String): UserId = transaction(purchase.db) {
        UserId.constructFromPersisted(UserTbl.select(UserTbl.id).where { UserTbl.phone eq phone }.single()[UserTbl.id].value)
    }

    private fun ordersOf(phone: String): Long = transaction(purchase.db) {
        val userId = userIdOf(phone).value
        OrderQmTbl.selectAll().where { OrderQmTbl.userId eq userId }.count()
    }

    private fun addressesOf(phone: String): Long = transaction(purchase.db) {
        val userId = userIdOf(phone).value
        AddressTbl.selectAll().where { AddressTbl.userId eq userId }.count()
    }

    private fun paymentStatusOf(paymentId: String): PaymentStatus = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.status).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.status]
    }

    private fun restock(variantId: Long, delta: Int) {
        others.stock(VariantId.constructFromPersisted(variantId)) {
            it.adjust(StockDelta.constructFromPersisted(delta), AdjustmentReason.RESTOCK, null, others.staff, clock.now)
        }
    }

    /** The placement a customer makes at a new address, with a key of its own: for calling the use case directly. */
    private fun command(userId: UserId, key: String = UUID.randomUUID().toString()) = PlaceOrderUc.Cmd(
        userId = userId,
        idempotencyKey = key,
        requestHash = RequestHash.of(key),
        addressId = null,
        newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null),
        newAddressAsDefault = null,
        saveNewAddress = false,
        messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
        shippingMethod = "POST",
        reserve = null,
        paymentGateway = "ZARINPAL",
        customerNote = null,
        gift = null,
        expectedPayable = null,
        acceptTerms = true,
    )

    private fun putInCart(userId: UserId, vararg variantIds: Long) = runBlocking {
        variantIds.forEach { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), it, 1)).getOrNull() ?: error("not added") }
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

    // ============ step 13 is done when ============

    @Test
    fun `the way it should go - 201, the order waits for its payment, the shopper goes to the gateway, the cart is empty and the stock held`() = app {
        val bearer = login(phone(901))
        add(bearer, nilaOlive)
        val addressId = addAddress(bearer)
        val before = purchase.availableOf(nilaOlive)

        val response = place(bearer, body(addressId = addressId, expectedPayable = 1_525_000))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val order = response.json().obj("order")
        val payment = response.json().obj("payment")
        assertTrue(Regex("KV-\\d{6}").matches(order.text("code")!!))
        assertEquals(listOf("PENDING_PAYMENT", "در انتظار پرداخت", "WARN"), listOf(order.text("status"), order.text("statusLabel"), order.text("statusTone")))
        assertEquals(1_525_000, order.long("payable"))
        assertEquals(listOf("POST", "پست معمولی"), listOf(order.obj("shippingMethod").text("code"), order.obj("shippingMethod").text("name")))
        assertEquals(1, order["itemsCount"]!!.jsonPrimitive.int)
        assertTrue(order.obj("actions")["canPay"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("UPCOMING"), order.obj("progress")["steps"]!!.jsonArray.map { it.jsonObject.text("state") }.distinct())
        assertEquals("بعد از تحویل به پست معمولی این‌جا نمایش داده می‌شه", order.obj("shipment").text("waitingMessage"))
        assertEquals(listOf("WAITING", "TELEGRAM", "تلگرام"), order.obj("preShipmentMedia").let { listOf(it.text("status"), it.text("channel"), it.text("channelName")) })
        assertEquals(JsonNull, order["reservation"])

        val paymentId = payment.text("paymentId")!!
        assertTrue(Regex("pay_[0-9A-Z]{12}").matches(paymentId))
        assertEquals(listOf("ZARINPAL", "GET"), listOf(payment.text("gateway"), payment.obj("redirect").text("method")))
        assertEquals(1_525_000, payment.long("amount"))
        assertTrue(payment.obj("redirect").text("url")!!.startsWith("http://localhost:8080/dev/fake-gateway/FAKE-"))
        assertEquals(Instant.parse(order.text("placedAt")!!).plus(Duration.ofMinutes(15)), Instant.parse(payment.text("expiresAt")!!))

        assertEquals(emptyList(), cartItems(bearer))
        assertEquals(before - 1, purchase.availableOf(nilaOlive))
        assertEquals(PaymentStatus.REDIRECTED, paymentStatusOf(paymentId))
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.REQUEST))
    }

    @Test
    fun `when several things are wrong, they are told one at a time in the contract's order, and nothing is saved`() = app {
        val minasAddress = addAddress(login(phone(902)))
        val bearer = login(phone(903))
        val own = addAddress(bearer)

        suspend fun refusal(json: String): String {
            val response = place(bearer, json)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, response.bodyAsText())
            return response.code()
        }

        assertEquals("CART_EMPTY", refusal(body(addressId = minasAddress, messenger = null, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        add(bearer, nilaOlive)
        val stock = purchase.availableOf(nilaOlive)
        assertEquals("ADDRESS_NOT_FOUND", refusal(body(addressId = minasAddress, messenger = null, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        assertEquals("MESSENGER_REQUIRED", refusal(body(addressId = own, messenger = null, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        val noChannel = buildJsonObject { put("phone", "09121112233") }
        assertEquals("MESSENGER_REQUIRED", refusal(body(addressId = own, messenger = noChannel, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        val wrongPhone = buildJsonObject {
            put("channel", "BALE")
            put("phone", "0912")
        }
        assertEquals("MESSENGER_PHONE_INVALID", refusal(body(addressId = own, messenger = wrongPhone, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        assertEquals("SHIPPING_METHOD_UNAVAILABLE", refusal(body(addressId = own, shippingMethod = "COURIER", gateway = "SAMAN", acceptTerms = false)))
        val noGateway = place(bearer, body(addressId = own, gateway = "SAMAN", acceptTerms = false))
        assertEquals("PAYMENT_GATEWAY_UNAVAILABLE", noGateway.code())
        assertEquals(JsonNull, noGateway.json()["meta"] ?: JsonNull, "before any order: no order code")
        assertEquals("TERMS_NOT_ACCEPTED", refusal(body(addressId = own, acceptTerms = false)))

        assertEquals(0, ordersOf(phone(903)))
        assertEquals(stock, purchase.availableOf(nilaOlive))
        assertEquals(1, cartItems(bearer).size)
        assertEquals(HttpStatusCode.Created, place(bearer, body(addressId = own)).status)
    }

    @Test
    fun `the shape comes first, every wrong field at once - a new address's under newAddress, and exactly one address`() = app {
        val bearer = login(phone(904))
        val all = buildJsonObject {
            put("newAddress", newAddress(postalCode = "123"))
            put("preShipmentMessenger", buildJsonObject { put("channel", "WHATSAPP"); put("username", "a".repeat(51)) })
            put("shippingMethod", "BIKE")
            put("expectedPayable", -1)
            put("customerNote", "ا".repeat(301))
            put("gift", buildJsonObject { put("message", "ا".repeat(201)) })
            put("acceptTerms", true)
        }.toString()

        // The cart is empty too, yet a malformed request is told so first.
        val wrong = place(bearer, all, key = "not-a-uuid")

        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(
            listOf(
                "Idempotency-Key IDEMPOTENCY_KEY_INVALID", "newAddress.postalCode POSTAL_CODE_INVALID", "preShipmentMessenger.channel MESSENGER_CHANNEL_INVALID",
                "preShipmentMessenger.username MESSENGER_USERNAME_TOO_LONG", "shippingMethod SHIPPING_METHOD_INVALID", "paymentGateway PAYMENT_GATEWAY_REQUIRED",
                "customerNote CUSTOMER_NOTE_TOO_LONG", "gift.message GIFT_MESSAGE_TOO_LONG", "expectedPayable MONEY_NEGATIVE",
            ),
            wrong.fieldErrors(),
        )
        assertEquals(listOf("addressId ADDRESS_AMBIGUOUS"), place(bearer, body(addressId = 1, newAddress = newAddress())).fieldErrors())
        assertEquals(listOf("addressId ADDRESS_REQUIRED"), place(bearer, body()).fieldErrors())
        assertEquals(listOf("Idempotency-Key IDEMPOTENCY_KEY_REQUIRED"), place(bearer, body(newAddress = newAddress()), key = null).fieldErrors())
    }

    @Test
    fun `a payable the shopper didn't see is 409 PRICE_CHANGED with the payable now, and nothing is saved, not even the key`() = app {
        val bearer = login(phone(905))
        add(bearer, nilaOlive)
        val stock = purchase.availableOf(nilaOlive)
        val key = UUID.randomUUID().toString()

        val changed = place(bearer, body(newAddress = newAddress(), expectedPayable = 1_450_000), key)

        assertEquals(HttpStatusCode.Conflict, changed.status)
        assertEquals("PRICE_CHANGED", changed.code())
        assertEquals(1_525_000, changed.json().obj("meta").long("payable"))
        assertEquals(1, cartItems(bearer).size)
        assertEquals(stock, purchase.availableOf(nilaOlive))
        assertEquals(0, ordersOf(phone(905)))
        assertEquals(0, addressesOf(phone(905)), "the new address isn't kept either")
        // The refusal left no record of the key: the same key with the right payable places the order.
        assertEquals(HttpStatusCode.Created, place(bearer, body(newAddress = newAddress(), expectedPayable = 1_525_000), key).status)
    }

    @Test
    fun `the last bags - 20 customers buy the last 2 Hasti cream at once, exactly 2 orders are placed and the rest are OUT_OF_STOCK`() {
        val customers = List(20) { purchase.customer(phone(920 + it)) }
        customers.forEach { putInCart(it, hastiCream) }
        assertEquals(2, purchase.availableOf(hastiCream))

        val results = atOnce(20) { purchase.placeOrder.execute(command(customers[it])) }

        assertEquals(2, results.count { it is Result.Success })
        assertEquals(setOf("OUT_OF_STOCK"), results.mapNotNull { it.failureOrNull()?.code }.toSet())
        assertEquals(0, purchase.availableOf(hastiCream))
    }

    @Test
    fun `no deadlock - orders of A and B and of B and A, and of two colors of one bag, at once, 50 times`() {
        listOf(termehLilac, termehBlack, rozhanCream).forEach { restock(it, 200) }
        // (A, B) against (B, A); a color and its sibling against the sibling alone; and against the first color alone.
        val shapes = listOf(
            listOf(termehLilac, rozhanCream) to listOf(rozhanCream, termehLilac),
            listOf(termehLilac, termehBlack) to listOf(termehBlack),
            listOf(termehBlack, termehLilac) to listOf(termehLilac),
        )

        repeat(50) { round ->
            val (first, second) = shapes[round % shapes.size]
            val a = purchase.customer(phone(1000 + 2 * round))
            val b = purchase.customer(phone(1001 + 2 * round))
            putInCart(a, *first.toLongArray())
            putInCart(b, *second.toLongArray())

            val results = atOnce(2) { purchase.placeOrder.execute(command(listOf(a, b)[it])) }

            assertEquals(listOf(true, true), results.map { it is Result.Success }, "round $round: $results")
        }
    }

    @Test
    fun `no deadlock - a color's price changed in the panel while it is being bought, at once, 20 times`() {
        restock(nilaOlive, 100)
        val nila = transaction(purchase.db) { ProductQmRepositoryImpl().findVariant(VariantId.constructFromPersisted(nilaOlive))!!.productId }
        // The panel's color form, built as the app builds it; the change log row goes to a staff id of its own.
        val updateVariant = UpdateVariantUc(
            purchase.transactions, ProductRepositoryImpl(), ProductInput(JsoupHtmlSanitizer), MediaAttachments(MediaRepositoryImpl()), AuditTrailImpl(), clock,
        )
        val staff = PanelActor(UserId.constructFromPersisted(7), null)

        repeat(20) { round ->
            val buyer = purchase.customer(phone(1200 + round))
            putInCart(buyer, nilaOlive)
            val price = if (round % 2 == 0) 1_450_000L else 1_390_000L
            val form = ProductInput.Variant(colorKey = "olive", priceOverride = price)

            val results = atOnce(2) {
                if (it == 0) {
                    purchase.placeOrder.execute(command(buyer))
                } else {
                    updateVariant.execute(UpdateVariantUc.Cmd(nila.value.toString(), nilaOlive.toString(), form, staff))
                }
            }

            assertTrue(results[1] is Result.Success, "round $round: ${results[1]}")
            // The order goes through, or (bought at the old price while the new one landed) is refused as a business rule.
            assertTrue(results[0] is Result.Success || results[0].failureOrNull()?.kind == FailureKind.BUSINESS_RULE, "round $round: ${results[0]}")
        }
    }

    @Test
    fun `two tabs - two orders at once from one cart, each with its own key, place one order and the other finds the cart empty`() {
        val userId = purchase.customer(phone(917))
        putInCart(userId, nilaOlive)
        val stock = purchase.availableOf(nilaOlive)
        // The second to have read the cart waits a while before going on: the first then commits in between, so only the
        // cart's lock can keep the second from placing a second order of it (the stock alone would let it, read afresh).
        val reads = AtomicInteger()
        val slowSecond = object : CartRepository by purchase.carts {
            override fun lockForUser(userId: UserId): Cart? = purchase.carts.lockForUser(userId).also {
                if (reads.getAndIncrement() == 1) Thread.sleep(1_500)
            }
        }
        val placeOrder = purchase.placeOrderWith(slowSecond)

        val results = atOnce(2) { placeOrder.execute(command(userId)) }

        assertEquals(1, results.count { it is Result.Success }, "$results")
        assertEquals(listOf("CART_EMPTY"), results.mapNotNull { it.failureOrNull()?.code })
        assertEquals(stock - 1, purchase.availableOf(nilaOlive))
    }

    @Test
    fun `a double click - two requests at once with one key place one order, and both answers have its code and its payment`() = app {
        val phone = phone(906)
        val bearer = login(phone)
        add(bearer, nilaOlive)
        val key = UUID.randomUUID().toString()
        val json = body(newAddress = newAddress())

        val answers = coroutineScope { List(2) { async { place(bearer, json, key) } }.awaitAll() }

        assertEquals(listOf(HttpStatusCode.Created, HttpStatusCode.Created), answers.map { it.status })
        val bodies = answers.map { it.json() }
        assertEquals(1, bodies.map { it.obj("order").text("code") }.distinct().size)
        assertEquals(1, bodies.map { it.obj("payment").text("paymentId") }.distinct().size)
        assertEquals(1, bodies.map { it.obj("payment").obj("redirect").text("url") }.distinct().size)
        assertEquals(1, ordersOf(phone))
    }

    @Test
    fun `the gateway doesn't answer after the order is placed - 422 with the order and its attempt, the stock stays held, and the same key gives the same 422`() = app {
        val phone = phone(907)
        val bearer = login(phone)
        add(bearer, nilaOlive)
        val stock = purchase.availableOf(nilaOlive)
        purchase.gateway.mode = FakePaymentGateway.Mode.UNREACHABLE
        val key = UUID.randomUUID().toString()
        val json = body(newAddress = newAddress())

        val first = place(bearer, json, key)

        assertEquals(HttpStatusCode.UnprocessableEntity, first.status)
        assertEquals("PAYMENT_GATEWAY_UNAVAILABLE", first.code())
        val meta = first.json().obj("meta")
        val orderCode = assertNotNull(meta.text("orderCode"))
        val paymentId = assertNotNull(meta.text("paymentId"))
        val order = transaction(purchase.db) { purchase.orderQm.findOfCustomer(userIdOf(phone), OrderCode.constructFromPersisted(orderCode)) }
        assertEquals(OrderStatus.PENDING_PAYMENT, order?.status)
        assertEquals(stock - 1, purchase.availableOf(nilaOlive))
        assertEquals(PaymentStatus.START_FAILED, paymentStatusOf(paymentId))
        assertEquals(listOf("UNREACHABLE"), transaction(purchase.db) {
            val row = PaymentTbl.select(PaymentTbl.id).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.id]
            PaymentLogTbl.select(PaymentLogTbl.outcome).where { PaymentLogTbl.paymentId eq row.value }.map { it[PaymentLogTbl.outcome] }
        })

        purchase.gateway.mode = FakePaymentGateway.Mode.NORMAL
        val again = place(bearer, json, key)
        assertEquals(HttpStatusCode.UnprocessableEntity, again.status)
        assertEquals(meta, again.json().obj("meta"))
        assertEquals(1, purchase.gateway.callsOf(FakePaymentGateway.REQUEST), "a repeat never opens another session")
        assertEquals(1, ordersOf(phone))
    }

    @Test
    fun `nothing to pay - no payment, the order is paid at once, and «پرداخت موفق» is sent`() = app {
        val phone = phone(908)
        val bearer = login(phone)
        add(bearer, nilaOlive)
        // A free bag shipped free: a test offer, since discount codes come in round 2.
        transaction(purchase.db) {
            ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq nilaOlive }) {
                it[price] = 0
                it[compareAtPrice] = null
            }
            ShopSettingsTbl.update { it[freeShippingThreshold] = 0 }
        }
        try {
            val response = place(bearer, body(newAddress = newAddress(), expectedPayable = 0))

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertEquals(JsonNull, response.json()["payment"])
            val order = response.json().obj("order")
            assertEquals(listOf("PROCESSING", "در حال آماده‌سازی"), listOf(order.text("status"), order.text("statusLabel")))
            assertEquals(listOf("CURRENT", "UPCOMING", "UPCOMING", "UPCOMING"), order.obj("progress")["steps"]!!.jsonArray.map { it.jsonObject.text("state") })
            assertEquals(0, purchase.gateway.callsOf(FakePaymentGateway.REQUEST))
            assertEquals(listOf(phone to order.text("code")), purchase.auth.sms.paid.map { it.first.value to it.second.value })
        } finally {
            transaction(purchase.db) { ShopSettingsTbl.update { it[freeShippingThreshold] = 3_000_000 } }
        }
    }

    @Test
    fun `a fourth unpaid order is 429 RATE_LIMITED, with how long until the first one stops holding its stock`() = app {
        val bearer = login(phone(909))
        val addressId = addAddress(bearer)
        repeat(3) {
            add(bearer, nilaOlive)
            assertEquals(HttpStatusCode.Created, place(bearer, body(addressId = addressId)).status)
            clock.advance(Duration.ofMinutes(1))
        }
        val firstHoldUntil = transaction(purchase.db) {
            OrderQmTbl.select(OrderQmTbl.holdUntil).where { OrderQmTbl.userId eq userIdOf(phone(909)).value }.minOf { it[OrderQmTbl.holdUntil].toInstant() }
        }
        add(bearer, nilaOlive)

        val fourth = place(bearer, body(addressId = addressId))

        assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
        assertEquals("RATE_LIMITED", fourth.code())
        val wait = Duration.between(clock.now, firstHoldUntil).seconds
        assertEquals(wait, fourth.json().obj("meta").long("retryAfterSeconds"))
        assertEquals(wait.toString(), fourth.headers[HttpHeaders.RetryAfter])
        assertEquals(1, cartItems(bearer).size)
    }

    @Test
    fun `a new address is kept in the book when asked, and with a full book the order is placed and the address isn't`() = app {
        val sara = phone(910)
        val bearer = login(sara)
        add(bearer, nilaOlive)

        val kept = place(bearer, body(newAddress = newAddress()))

        assertEquals(HttpStatusCode.Created, kept.status)
        val addresses = client.get("$API_V1/me/addresses") { header(HttpHeaders.Authorization, "Bearer $bearer") }.let { Json.parseToJsonElement(it.bodyAsText()).jsonArray }
        assertEquals(1, addresses.size)
        val keptCode = OrderCode.constructFromPersisted(kept.json().obj("order").text("code")!!)
        val order = transaction(purchase.db) { purchase.orderQm.findOfCustomer(userIdOf(sara), keptCode) }
        assertEquals(addresses.single().jsonObject.long("id"), order?.address?.addressId?.value)

        add(bearer, nilaOlive)
        val notKept = place(bearer, body(newAddress = newAddress(), saveNewAddress = false))
        assertEquals(HttpStatusCode.Created, notKept.status)

        val mina = phone(911)
        val minasBearer = login(mina)
        val minaId = userIdOf(mina)
        repeat(20) {
            val fields = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "مینا احمدی", "09123456789", true, null, null)
            assertTrue(purchase.addAddress.execute(AddAddressUc.Cmd(minaId, fields, null)) is Result.Success)
        }
        add(minasBearer, nilaOlive)

        val full = place(minasBearer, body(newAddress = newAddress()))

        assertEquals(HttpStatusCode.Created, full.status, full.bodyAsText())
        val fullCode = OrderCode.constructFromPersisted(full.json().obj("order").text("code")!!)
        val minasOrder = transaction(purchase.db) { purchase.orderQm.findOfCustomer(minaId, fullCode) }
        assertNull(minasOrder?.address?.addressId)
        assertEquals(
            20,
            client.get("$API_V1/me/addresses") { header(HttpHeaders.Authorization, "Bearer $minasBearer") }.let { Json.parseToJsonElement(it.bodyAsText()).jsonArray.size },
        )
        assertEquals(1, addressesOf(sara), "an address not asked to be kept isn't")
    }

    @Test
    fun `saveAsDefault keeps the order's messenger as the customer's default, and without it the default stays`() = app {
        val phone = phone(912)
        val bearer = login(phone)
        add(bearer, nilaOlive)

        place(bearer, body(newAddress = newAddress()))

        fun defaults() = transaction(purchase.db) {
            UserTbl.select(UserTbl.defaultMessenger, UserTbl.defaultMessengerPhone).where { UserTbl.phone eq phone }.single()
                .let { it[UserTbl.defaultMessenger] to it[UserTbl.defaultMessengerPhone] }
        }
        assertEquals(MessengerChannel.TELEGRAM to "09121112233", defaults())

        add(bearer, nilaOlive)
        val bale = buildJsonObject {
            put("channel", "BALE")
            put("phone", "09124445566")
            put("saveAsDefault", false)
        }
        assertEquals(HttpStatusCode.Created, place(bearer, body(newAddress = newAddress(), messenger = bale)).status)
        assertEquals(MessengerChannel.TELEGRAM to "09121112233", defaults())
    }

    @Test
    fun `the order keeps what it was - a new price or an edited address later changes nothing of it`() = app {
        val phone = phone(913)
        val bearer = login(phone)
        add(bearer, nilaOlive)
        val addressId = addAddress(bearer)
        val code = place(bearer, body(addressId = addressId)).json().obj("order").text("code")!!
        fun order() = transaction(purchase.db) { purchase.orderQm.findOfCustomer(userIdOf(phone), OrderCode.constructFromPersisted(code))!! }
        val before = order()

        transaction(purchase.db) { ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq nilaOlive }) { it[price] = 1_600_000 } }
        val edited = client.put("$API_V1/me/addresses/$addressId") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(newAddress().toString().replace("کوچه نسترن", "کوچه یاس"))
        }
        assertEquals(HttpStatusCode.OK, edited.status)

        val after = order()
        assertEquals(before.lines, after.lines)
        assertEquals(before.address, after.address)
        assertEquals(1_450_000, after.lines.single().priceTag.price.toman)
        assertEquals("خیابان ولیعصر، کوچه نسترن", after.address.line.value)
        val stored = transaction(purchase.db) { purchase.orders.get(after.orderId)!! }
        assertEquals(before.lines, stored.lines)
    }

    @Test
    fun `a color taken out of the shop since it was added is PRODUCT_UNAVAILABLE, naming the cart's line`() = app {
        val bearer = login(phone(914))
        add(bearer, aidaBlack)
        add(bearer, nilaOlive)
        val nilaLine = cartItems(bearer).single { it.long("variantId") == nilaOlive }.text("id")
        transaction(purchase.db) { ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq nilaOlive }) { it[isActive] = false } }

        val refused = place(bearer, body(newAddress = newAddress()))

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("PRODUCT_UNAVAILABLE", refused.code())
        assertEquals(listOf(nilaLine), refused.json().obj("meta")["itemIds"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `placing and starting the payment are told apart by their ids - what the use case returns names the order and its attempt`() {
        val userId = purchase.customer(phone(915))
        putInCart(userId, nilaOlive)

        val placed = runBlocking { purchase.placeOrder.execute(command(userId)) }.getOrNull()!!

        assertEquals(PlacedOrder(placed.orderCode, placed.paymentId), PlacedOrder.fromOutcome(placed.outcome()))
        assertNotEquals(null, placed.paymentId)
        assertEquals(PaymentStatus.INITIATED, paymentStatusOf(placed.paymentId!!.value))
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, placed.paymentId!!)) }
        assertEquals(PaymentStatus.REDIRECTED, paymentStatusOf(placed.paymentId!!.value))
        val someoneElse = purchase.customer(phone(916))
        assertEquals(
            "PAYMENT_NOT_FOUND",
            runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(someoneElse, PaymentPublicId.constructFromPersisted(placed.paymentId!!.value))) }.failureOrNull()?.code,
        )
    }
}
