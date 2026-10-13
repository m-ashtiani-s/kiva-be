package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ApplyDiscountCodeUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.application.usecase.discount.Discounts
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.DiscountDomainService
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountType
import com.kiva.domain.aggregate.discount.repository.DiscountRedemptionRepository
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.CART_TOKEN_HEADER
import com.kiva.infrastructure.httpserver.IDEMPOTENCY_KEY_HEADER
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.Companion.priceTag
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountCodeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.DiscountRedemptionTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.seed.DevDiscountCodeSeeder
import com.kiva.infrastructure.seed.DiscountCodeRow
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discount codes (round 2) over the real stack on kiva_test: applied to the cart, checked again after every change and
 * when the order is placed (under the code's lock), held, confirmed by the payment and given back by the expiry.
 * Step 20 of the purchase roadmap is done when these pass.
 *
 * The seeded bags used: «کیف دستی آیدا» caramel (2,790,000 instead of 3,290,000), «کیف پول نازلی» brown (690,000 instead
 * of 790,000), «کراس‌بادی نیلا» olive (1,450,000 instead of 1,750,000), «کیف پول ترمه» lilac (590,000, not on sale).
 * Post is 75,000, free from a subtotal of 3,000,000.
 */
class WebV1DiscountCodesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
        DevDiscountCodeSeeder.CODES.forEach(purchase::discountCode)
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = purchase.app(block)

    private val aidaCaramel by lazy { purchase.variantOf("aida-handbag", "caramel") }
    private val nazliBrown by lazy { purchase.variantOf("nazli-wallet", "brown") }
    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val termehLilac by lazy { purchase.variantOf("termeh-wallet", "lilac") }

    // ============ step 20 is done when ============

    @Test
    fun `KIVA10 on 3,480,000 - 348,000 off, 3,132,000 to pay, post still free, the order keeps it and the payment confirms its use`() = app {
        val bearer = login(phone(2001))
        add(bearer, aidaCaramel)
        add(bearer, nazliBrown)

        val applied = applyCode(bearer, "KIVA10")

        assertEquals(HttpStatusCode.OK, applied.status, applied.bodyAsText())
        val cart = applied.json()
        val discount = cart.obj("discount")
        assertEquals(listOf("KIVA10", "۱۰٪ تخفیف روی کل سبد", "PERCENT", "10", "348000"), listOf("code", "label", "type", "value", "amount").map { discount.text(it) })
        val totals = cart.obj("totals")
        assertEquals(
            listOf("3480000", "348000", "0", "3132000", "948000"),
            listOf("subtotal", "codeDiscount", "shippingCost", "payable", "totalSavings").map { totals.text(it) },
        )

        val checkout = client.get("$API_V1/checkout") { bearer(bearer) }.json().obj("cart")
        assertEquals("3132000", checkout.obj("totals").text("payable"))
        assertEquals("348000", checkout.obj("discount").text("amount"))

        val placed = place(bearer, body(addAddress(bearer), expectedPayable = 3_132_000))
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val order = placed.json()
        assertEquals("3132000", order.obj("payment").text("amount"))
        val code = order.obj("order").text("code")!!
        val detail = client.get("$API_V1/me/orders/$code") { bearer(bearer) }.json().obj("totals")
        assertEquals(listOf("KIVA10", "348000", "3132000"), listOf(detail.text("discountCode"), detail.text("codeDiscount"), detail.text("payable")))
        assertEquals(DiscountRedemptionStatus.HELD to 348_000L, redemptionOf(code))
        assertEquals(JsonNull, cartOf(bearer)["discount"])

        val paymentId = order.obj("payment").text("paymentId")!!
        val authority = authorityOf(paymentId)
        assertTrue(purchase.gateway.pay(authority))
        browser().get("$API_V1/payment-callbacks/zarinpal?Authority=$authority&Status=OK")

        assertEquals(DiscountRedemptionStatus.CONFIRMED to 348_000L, redemptionOf(code))
        assertEquals(OrderStatus.PROCESSING, statusOf(code))
    }

    @Test
    fun `rounding to the nearest thousand, a half up - 145,500 is 146,000 and 145,499 is 145,000`() = app {
        val bearer = login(phone(2002))
        val half = newBag(1_455_000)
        add(bearer, half)
        assertEquals("146000", applyCode(bearer, "KIVA10").json().obj("discount").text("amount"))

        remove(bearer, half)
        add(bearer, newBag(1_454_990))
        val cart = applyCode(bearer, "KIVA10").json()
        assertEquals("145000", cart.obj("discount").text("amount"))
        assertEquals("1384990", cart.obj("totals").text("payable"))
    }

    @Test
    fun `PAEEZ15 under 2,000,000 - 422 DISCOUNT_MIN_SUBTOTAL_NOT_MET with the minimum and its sentence, and the cart keeps no code`() = app {
        val token = guestAdd(nilaOlive)

        val refused = client.post("$API_V1/cart/discount-code") {
            header(CART_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"PAEEZ15"}""")
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status, refused.bodyAsText())
        val problem = refused.json()
        assertEquals("DISCOUNT_MIN_SUBTOTAL_NOT_MET", problem.text("code"))
        assertEquals("2000000", problem.obj("meta").text("minSubtotal"))
        assertEquals("این کد برای خرید بالای ۲٬۰۰۰٬۰۰۰ تومان فعاله", problem.text("message"))
        assertEquals(JsonNull, client.get("$API_V1/cart") { header(CART_TOKEN_HEADER, token) }.json()["discount"])
    }

    @Test
    fun `WELCOME - a guest must log in, a customer who paid before is refused, and a first cart of 100,000 pays the shipping only`() = app {
        val token = guestAdd(termehLilac)
        val guest = client.post("$API_V1/cart/discount-code") {
            header(CART_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"WELCOME"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, guest.status)
        assertEquals("DISCOUNT_LOGIN_REQUIRED", guest.json().text("code"))

        val returning = login(phone(2003))
        add(returning, termehLilac)
        val paidBefore = paidOrder(phone(2003))
        assertEquals(OrderStatus.PROCESSING, statusOf(paidBefore))
        add(returning, termehLilac)
        val refused = applyCode(returning, "WELCOME")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals(listOf("DISCOUNT_FIRST_ORDER_ONLY", "این کد فقط برای خرید اوله"), listOf(refused.json().text("code"), refused.json().text("message")))

        val first = login(phone(2004))
        add(first, newBag(100_000))
        val cart = applyCode(first, "WELCOME").json()
        assertEquals("100000", cart.obj("discount").text("amount"))
        assertEquals(listOf("100000", "75000", "75000"), listOf("codeDiscount", "shippingCost", "payable").map { cart.obj("totals").text(it) })
    }

    @Test
    fun `a code that covers the whole order - no payment, the order PROCESSING at once, and its use confirmed`() = app {
        purchase.discountCode(DiscountCodeRow("GIFT100", DiscountType.PERCENT, 100, "هدیه‌ی کامل"))
        val bearer = login(phone(2005))
        add(bearer, aidaCaramel)
        add(bearer, nazliBrown)
        assertEquals("0", applyCode(bearer, "GIFT100").json().obj("totals").text("payable"))

        val placed = place(bearer, body(addAddress(bearer), expectedPayable = 0))

        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        assertEquals(JsonNull, placed.json()["payment"])
        val code = placed.json().obj("order").text("code")!!
        assertEquals("PROCESSING", placed.json().obj("order").text("status"))
        assertEquals(DiscountRedemptionStatus.CONFIRMED to 3_480_000L, redemptionOf(code))
    }

    @Test
    fun `usageLimit 1 and two orders at the same moment - one gets the code, the other 409 PRICE_CHANGED without it`() {
        purchase.discountCode(DiscountCodeRow("ONCE", DiscountType.FIXED, 100_000, "یک بار", usageLimit = 1))
        // Two bags of two products: their stock never makes one order wait for the other, only the code's row does.
        val sara = purchase.customer(phone(2006)).also { putInCartWithCode(it, nazliBrown, "ONCE") }
        val mina = purchase.customer(phone(2007)).also { putInCartWithCode(it, termehLilac, "ONCE") }
        val withCode = mapOf(sara to 690_000L - 100_000 + 75_000, mina to 590_000L - 100_000 + 75_000)
        // Each reads the code's uses slowly: without the code's row lock both would read none and both would take it.
        val slow = purchase.placeOrderWith(
            purchase.carts,
            Discounts(purchase.discountCodes, SlowUsage(purchase.redemptions), purchase.orderQm, DiscountDomainService()),
        )

        val customers = listOf(sara, mina)
        val results = atOnce(2) { index -> slow.execute(command(customers[index], expectedPayable = withCode.getValue(customers[index]))) }

        assertEquals(1, results.count { it is Result.Success }, "$results")
        val (refusedIndex, refused) = results.withIndex().single { it.value is Result.Failure }.let { it.index to it.value.failureOrNull()!! }
        assertEquals("PRICE_CHANGED" to mapOf<String, Any>("payable" to withCode.getValue(customers[refusedIndex]) + 100_000), refused.code to refused.meta)
        val placed = results.firstNotNullOf { it.getOrNull() }
        assertEquals(DiscountRedemptionStatus.HELD to 100_000L, redemptionOf(placed.orderCode.value))
        assertEquals(1, usesOf("ONCE"))
    }

    @Test
    fun `an order that expires gives its use back - the code can be used again`() = app {
        purchase.discountCode(DiscountCodeRow("ONCE", DiscountType.FIXED, 100_000, "یک بار", usageLimit = 1))
        val sara = login(phone(2008))
        add(sara, nazliBrown)
        applyCode(sara, "ONCE")
        val placed = place(sara, body(addAddress(sara), expectedPayable = 665_000))
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        val code = placed.json().obj("order").text("code")!!

        val mina = login(phone(2009))
        add(mina, nazliBrown)
        assertEquals("DISCOUNT_USAGE_LIMIT_REACHED", applyCode(mina, "ONCE").json().text("code"))

        // Past the time to pay and the gateway's window.
        clock.advance(Duration.ofMinutes(36))
        purchase.jobs.expireUnpaidOrders()

        assertEquals(OrderStatus.EXPIRED, statusOf(code))
        assertEquals(DiscountRedemptionStatus.RELEASED to 100_000L, redemptionOf(code))
        val again = login(phone(2009))
        assertEquals("100000", applyCode(again, "ONCE").json().obj("discount").text("amount"))
    }

    @Test
    fun `a code that expires while in the cart is taken off with DISCOUNT_REMOVED, told once`() = app {
        purchase.discountCode(DiscountCodeRow("YALDA", DiscountType.PERCENT, 20, "شب یلدا", endsAt = clock.now.plus(Duration.ofMinutes(5))))
        val bearer = login(phone(2010))
        add(bearer, nilaOlive)
        assertEquals("290000", applyCode(bearer, "YALDA").json().obj("discount").text("amount"))

        clock.advance(Duration.ofMinutes(6))
        val looked = cartOf(bearer)

        assertEquals(JsonNull, looked["discount"])
        assertEquals("0", looked.obj("totals").text("codeDiscount"))
        val issues = looked["issues"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("DISCOUNT_REMOVED"), issues.map { it.text("code") })
        assertEquals("کد تخفیف «YALDA» دیگه روی سبدت اعمال نمی‌شه و برداشته شد.", issues.single().text("message"))
        assertEquals(emptyList(), cartOf(bearer)["issues"]!!.jsonArray.toList())
    }

    @Test
    fun `excludeSaleItems - only bags without a crossed-out price are its base, with only sale items, NOT_APPLICABLE`() = app {
        purchase.discountCode(DiscountCodeRow("FULLPRICE10", DiscountType.PERCENT, 10, "۱۰٪ روی کالاهای بدون تخفیف", excludeSaleItems = true))
        val bearer = login(phone(2011))
        add(bearer, nilaOlive)

        val refused = applyCode(bearer, "FULLPRICE10")
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        assertEquals("DISCOUNT_NOT_APPLICABLE", refused.json().text("code"))

        add(bearer, termehLilac)
        assertEquals("59000", applyCode(bearer, "FULLPRICE10").json().obj("discount").text("amount"))

        // The bag without a sale price goes: nothing is covered any more, so the code goes too.
        val left = remove(bearer, termehLilac).json().obj("cart")
        assertEquals(JsonNull, left["discount"])
        assertEquals(listOf("DISCOUNT_REMOVED"), left["issues"]!!.jsonArray.map { it.jsonObject.text("code") })
    }

    @Test
    fun `kiva10 with a space and KIVA with Persian digits are KIVA10, nothing typed is a 400, a code that isn't there a 422`() = app {
        val bearer = login(phone(2012))
        add(bearer, nilaOlive)

        assertEquals("KIVA10", applyCode(bearer, "kiva10 ").json().obj("discount").text("code"))
        assertEquals("KIVA10", applyCode(bearer, "KIVA۱۰").json().obj("discount").text("code"))

        val blank = applyCode(bearer, "  ")
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(listOf("code DISCOUNT_CODE_REQUIRED"), blank.json()["errors"]!!.jsonArray.map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" })
        listOf("NOPE", "کیوا", "K").forEach { unknown ->
            val response = applyCode(bearer, unknown)
            assertEquals(HttpStatusCode.UnprocessableEntity, response.status, unknown)
            assertEquals(listOf("DISCOUNT_CODE_INVALID", "این کد تخفیف معتبر نیست"), listOf(response.json().text("code"), response.json().text("message")))
        }
        // A refused code leaves the one the cart had.
        assertEquals("KIVA10", cartOf(bearer).obj("discount").text("code"))
    }

    // ============ and around it ============

    @Test
    fun `taking the code off brings the payable back`() = app {
        val bearer = login(phone(2013))
        add(bearer, nilaOlive)
        applyCode(bearer, "KIVA10")

        val removed = client.delete("$API_V1/cart/discount-code") { bearer(bearer) }

        assertEquals(HttpStatusCode.OK, removed.status, removed.bodyAsText())
        assertEquals(JsonNull, removed.json()["discount"])
        assertEquals("1525000", removed.json().obj("totals").text("payable"))
        assertEquals(emptyList(), removed.json()["issues"]!!.jsonArray.toList())
        assertEquals(HttpStatusCode.OK, client.delete("$API_V1/cart/discount-code") { bearer(bearer) }.status)
    }

    @Test
    fun `a code that stopped applying between the cart and the order - 409 PRICE_CHANGED with the payable without it, then placed without it`() = app {
        purchase.discountCode(DiscountCodeRow("SOON", DiscountType.FIXED, 200_000, "زودگذر", endsAt = clock.now.plus(Duration.ofMinutes(5))))
        val bearer = login(phone(2014))
        add(bearer, nilaOlive)
        assertEquals("1325000", applyCode(bearer, "SOON").json().obj("totals").text("payable"))
        val addressId = addAddress(bearer)

        clock.advance(Duration.ofMinutes(10))
        val changed = place(bearer, body(addressId, expectedPayable = 1_325_000))

        assertEquals(HttpStatusCode.Conflict, changed.status, changed.bodyAsText())
        assertEquals("PRICE_CHANGED", changed.json().text("code"))
        assertEquals("1525000", changed.json().obj("meta").text("payable"))
        assertEquals(0, ordersOf(phone(2014)))

        val placed = place(bearer, body(addressId, expectedPayable = 1_525_000))
        assertEquals(HttpStatusCode.Created, placed.status, placed.bodyAsText())
        assertNull(redemptionOrNull(placed.json().obj("order").text("code")!!))
    }

    @Test
    fun `after a login the guest's code comes along, checked for the customer`() = app {
        val token = guestAdd(nilaOlive)
        client.post("$API_V1/cart/discount-code") {
            header(CART_TOKEN_HEADER, token)
            contentType(ContentType.Application.Json)
            setBody("""{"code":"KIVA10"}""")
        }
        val bearer = login(phone(2015))

        val merged = client.post("$API_V1/cart/merge") {
            bearer(bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"guestCartToken":"$token"}""")
        }

        assertEquals(HttpStatusCode.OK, merged.status, merged.bodyAsText())
        assertEquals(listOf("KIVA10", "145000"), listOf(merged.json().obj("discount").text("code"), merged.json().obj("discount").text("amount")))
    }

    @Test
    fun `the dev seeder's three codes, once`() {
        val seeder = DevDiscountCodeSeeder(purchase.transactions, clock)
        assertEquals(0, runBlocking { seeder.seed() })
        val codes = transaction(purchase.db) { DiscountCodeTbl.selectAll().associate { it[DiscountCodeTbl.code] to it[DiscountCodeTbl.value] } }
        assertEquals(mapOf("KIVA10" to 10, "WELCOME" to 150_000, "PAEEZ15" to 15), codes)
    }

    // ============ helpers ============

    /** Reads a code's uses as the real repository does, slowly, so two orders read them at the same moment. */
    private class SlowUsage(private val real: DiscountRedemptionRepository) : DiscountRedemptionRepository by real {
        override fun usageOf(codeId: DiscountCodeId, userId: UserId?) =
            real.usageOf(codeId, userId).also { Thread.sleep(1_500) }
    }

    /** The shopper's browser back from the gateway: it doesn't follow the callback's redirect to the frontend. */
    private fun ApplicationTestBuilder.browser(): HttpClient = createClient { followRedirects = false }

    private fun HttpRequestBuilder.bearer(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
    }

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
    private suspend fun ApplicationTestBuilder.add(bearer: String, variantId: Long) {
        clock.advance(Duration.ofSeconds(1))
        val response = client.post("$API_V1/cart/items") {
            bearer(bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    /** A guest's first line; returns the cart's new token. */
    private suspend fun ApplicationTestBuilder.guestAdd(variantId: Long): String {
        val response = client.post("$API_V1/cart/items") {
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":1}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.headers[CART_TOKEN_HEADER]!!
    }

    private suspend fun ApplicationTestBuilder.remove(bearer: String, variantId: Long): HttpResponse {
        val itemId = cartOf(bearer)["items"]!!.jsonArray.map { it.jsonObject }.single { it.text("variantId") == variantId.toString() }.text("id")
        return client.delete("$API_V1/cart/items/$itemId") { bearer(bearer) }
    }

    private suspend fun ApplicationTestBuilder.applyCode(bearer: String, code: String): HttpResponse =
        client.post("$API_V1/cart/discount-code") {
            bearer(bearer)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("code", code) }.toString())
        }

    private suspend fun ApplicationTestBuilder.cartOf(bearer: String): JsonObject = client.get("$API_V1/cart") { bearer(bearer) }.json()

    private suspend fun ApplicationTestBuilder.addAddress(bearer: String): Long {
        val response = client.post("$API_V1/me/addresses") {
            bearer(bearer)
            contentType(ContentType.Application.Json)
            setBody(
                """{"provinceId":8,"cityId":394,"addressLine":"خیابان ولیعصر، کوچه نسترن","postalCode":"1965843117",""" +
                    """"recipientName":"سارا محمدی","recipientPhone":"09123456789"}""",
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json()["id"]!!.jsonPrimitive.long
    }

    private fun body(addressId: Long, expectedPayable: Long): String =
        """{"addressId":$addressId,"preShipmentMessenger":{"channel":"TELEGRAM","phone":"09121112233"},"shippingMethod":"POST",""" +
            """"paymentGateway":"ZARINPAL","expectedPayable":$expectedPayable,"acceptTerms":true}"""

    private suspend fun ApplicationTestBuilder.place(bearer: String, json: String): HttpResponse =
        client.post("$API_V1/orders") {
            bearer(bearer)
            header(IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    /** A bag of its own at [price], in the shop with plenty left: for amounts the seeded bags don't have. */
    private fun newBag(price: Long): Long {
        val product = others.newProduct(listOf(StoredCatalog.NewColor("lilac")), priceTag(price))
        others.change(product.id) { it.publish(others.staff, clock.now) }
        val variantId = product.variants.single().id
        others.stock(variantId) { it.adjust(StockDelta.constructFromPersisted(10), AdjustmentReason.RESTOCK, null, others.staff, clock.now) }
        return variantId.value
    }

    /** The customer's cart, then [code] applied, as the use cases do it: for tests that place orders directly. */
    private fun putInCartWithCode(userId: UserId, variantId: Long, code: String) = runBlocking {
        purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), variantId, 1)).getOrNull() ?: error("not added")
        purchase.cartUseCases.applyDiscountCode.execute(ApplyDiscountCodeUc.Cmd(CartCaller.Customer(userId), code)).getOrNull() ?: error("not applied")
    }

    /** The customer's cart placed as an order, and paid at the gateway: what makes WELCOME refuse them. */
    private suspend fun ApplicationTestBuilder.paidOrder(phone: String): String {
        val userId = purchase.customer(phone)
        val placed = purchase.placeOrder.execute(command(userId, expectedPayable = null)).getOrNull() ?: error("not placed")
        val paymentId = checkNotNull(placed.paymentId)
        purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)).getOrNull() ?: error("not started")
        val authority = authorityOf(paymentId.value)
        check(purchase.gateway.pay(authority))
        browser().get("$API_V1/payment-callbacks/zarinpal?Authority=$authority&Status=OK")
        return placed.orderCode.value
    }

    private fun command(userId: UserId, expectedPayable: Long?): PlaceOrderUc.Cmd {
        val key = UUID.randomUUID().toString()
        return PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
            newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null),
            newAddressAsDefault = null, saveNewAddress = false,
            messenger = PlaceOrderUc.MessengerFields("TELEGRAM", "09121112233", null, null, saveAsDefault = false),
            shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = expectedPayable,
            acceptTerms = true,
        )
    }

    private fun authorityOf(paymentId: String): String = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]!!
    }

    private fun orderIdOf(code: String): Long = transaction(purchase.db) {
        OrderQmTbl.select(OrderQmTbl.orderId).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.orderId]
    }

    private fun redemptionOrNull(code: String): Pair<DiscountRedemptionStatus, Long>? = transaction(purchase.db) {
        DiscountRedemptionTbl.selectAll().where { DiscountRedemptionTbl.orderId eq orderIdOf(code) }.singleOrNull()
            ?.let { it[DiscountRedemptionTbl.status] to it[DiscountRedemptionTbl.amount] }
    }

    private fun redemptionOf(code: String): Pair<DiscountRedemptionStatus, Long> = redemptionOrNull(code) ?: error("Order $code used no code")

    private fun usesOf(code: String): Int = transaction(purchase.db) {
        val codeId = DiscountCodeTbl.select(DiscountCodeTbl.id).where { DiscountCodeTbl.code eq code }.single()[DiscountCodeTbl.id].value
        DiscountRedemptionTbl.selectAll().where { DiscountRedemptionTbl.codeId eq codeId }.count().toInt()
    }

    private fun statusOf(code: String): OrderStatus = transaction(purchase.db) {
        OrderQmTbl.select(OrderQmTbl.status).where { OrderQmTbl.code eq code }.single()[OrderQmTbl.status]
    }

    private fun ordersOf(phone: String): Long = transaction(purchase.db) {
        val userId = purchase.customer(phone).value
        OrderQmTbl.selectAll().where { OrderQmTbl.userId eq userId }.count()
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

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject
}
