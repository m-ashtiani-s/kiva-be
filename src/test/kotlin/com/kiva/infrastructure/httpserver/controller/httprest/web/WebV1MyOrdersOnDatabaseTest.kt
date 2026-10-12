package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.admin.usecase.query.FindCustomersUc
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.HandleGatewayCallbackUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.application.usecase.checkout.usecase.command.StartPaymentUc
import com.kiva.common.pagination.Pageable
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «سفارش‌های من» and «خرید دوباره» over the real stack on kiva_test, with orders placed, paid, cancelled and expired the
 * way the shop does it. Step 17 of the purchase roadmap is done when these pass.
 */
class WebV1MyOrdersOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val purchase = PurchaseTestStack(clock)
    private val seeded = SeededCatalog(clock)

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        purchase.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nilaOlive by lazy { purchase.variantOf("nila-crossbody-bag", "olive") }
    private val aidaBlack by lazy { purchase.variantOf("aida-handbag", "black") }
    private val termehBlack by lazy { purchase.variantOf("termeh-wallet", "black") }

    /**
     * An order of [userId] of [variants], one each, sent to the gateway. Each bag goes into the cart a second after the one
     * before, as in real life, so the order's lines are numbered in the order given.
     */
    private fun place(userId: UserId, vararg variants: Long): Pair<OrderCode, String> {
        variants.forEach {
            clock.advance(Duration.ofSeconds(1))
            runBlocking { purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(userId), it, 1)) }.getOrNull() ?: error("not added")
        }
        val key = UUID.randomUUID().toString()
        val command = PlaceOrderUc.Cmd(
            userId = userId, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
            newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", "12", "4", "1965843117", "سارا محمدی", "09123456789", true, null, null),
            newAddressAsDefault = null, saveNewAddress = false, messenger = PlaceOrderUc.MessengerFields("BALE", "09121112233", null, "از داخل کیف هم عکس بگیرید", saveAsDefault = false),
            shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = "زنگ نزنید", gift = null, expectedPayable = null, acceptTerms = true,
        )
        val order = runBlocking { purchase.placeOrder.execute(command) }.getOrNull() ?: error("not placed")
        val paymentId = checkNotNull(order.paymentId)
        runBlocking { purchase.startPayment.execute(StartPaymentUc.Cmd(userId, paymentId)) }
        return order.orderCode to paymentId.value
    }

    private fun authorityOf(paymentId: String): String = transaction(purchase.db) {
        PaymentTbl.select(PaymentTbl.authority).where { PaymentTbl.publicId eq paymentId }.single()[PaymentTbl.authority]!!
    }

    private fun back(paymentId: String, paid: Boolean) {
        val authority = authorityOf(paymentId)
        if (paid) purchase.gateway.pay(authority) else purchase.gateway.cancel(authority)
        runBlocking { purchase.handleCallback.execute(HandleGatewayCallbackUc.Cmd("zarinpal", mapOf("Authority" to authority, "Status" to if (paid) "OK" else "NOK"))) }
    }

    private fun expireDue() = runBlocking { purchase.jobs.expireUnpaidOrders() }

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

    private suspend fun ApplicationTestBuilder.call(bearer: String, path: String): HttpResponse =
        client.get("$API_V1$path") { header(HttpHeaders.Authorization, "Bearer $bearer") }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.obj(name: String): JsonObject = this[name]!!.jsonObject

    private fun JsonObject.array(name: String): JsonArray = this[name]!!.jsonArray

    /** Sara's four orders, oldest first: expired, paid, cancelled at the bank, and waiting for its payment. */
    private class Saras(val expired: OrderCode, val paid: OrderCode, val failed: OrderCode, val waiting: OrderCode)

    private fun sarasOrders(n: Int): Saras {
        val sara = purchase.customer(phone(n))
        val (expired, expiredPayment) = place(sara, nilaOlive)
        back(expiredPayment, paid = false)
        clock.advance(Duration.ofMinutes(16))
        expireDue()
        val (paid, paidPayment) = place(sara, nilaOlive, aidaBlack)
        back(paidPayment, paid = true)
        val (failed, failedPayment) = place(sara, nilaOlive)
        back(failedPayment, paid = false)
        val (waiting, _) = place(sara, nilaOlive)
        return Saras(expired, paid, failed, waiting)
    }

    // ============ step 17 is done when ============

    @Test
    fun `the list is newest first, paged, filtered by tab, and counts every tab - an expired order is cancelled`() = app {
        val orders = sarasOrders(1301)
        val bearer = login(phone(1301))

        val all = call(bearer, "/me/orders").json()

        assertEquals(listOf(orders.waiting, orders.failed, orders.paid, orders.expired).map { it.value }, all.array("items").map { it.jsonObject.text("code") })
        assertEquals(mapOf("all" to 4L, "current" to 3L, "delivered" to 0L, "cancelled" to 1L), all.obj("counts").mapValues { it.value.jsonPrimitive.long })
        assertEquals(listOf("1", "20", "4", "1", "false"), listOf("page", "size", "totalItems", "totalPages", "hasNext").map { all.obj("meta").text(it) })
        assertEquals(
            listOf(
                listOf("PENDING_PAYMENT", "در انتظار پرداخت", "WARN"), listOf("PAYMENT_FAILED", "پرداخت ناموفق", "DANGER"),
                listOf("PROCESSING", "در حال آماده‌سازی", "WARN"), listOf("EXPIRED", "منقضی شد", "DANGER"),
            ),
            all.array("items").map { it.jsonObject.let { o -> listOf(o.text("status"), o.text("statusLabel"), o.text("statusTone")) } },
        )
        assertEquals(listOf(true, true, false, false), all.array("items").map { it.jsonObject.obj("actions")["canPay"]!!.jsonPrimitive.boolean })

        fun codesOf(json: JsonObject) = json.array("items").map { it.jsonObject.text("code") }
        assertEquals(listOf(orders.waiting, orders.failed, orders.paid).map { it.value }, codesOf(call(bearer, "/me/orders?filter=current").json()))
        assertEquals(listOf(orders.expired.value), codesOf(call(bearer, "/me/orders?filter=cancelled").json()))
        val delivered = call(bearer, "/me/orders?filter=delivered").json()
        assertEquals(emptyList(), codesOf(delivered))
        assertEquals(4L, delivered.obj("counts")["all"]!!.jsonPrimitive.long, "the counts are every tab's, whichever is shown")

        val second = call(bearer, "/me/orders?size=2&page=2").json()
        assertEquals(listOf(orders.paid, orders.expired).map { it.value }, codesOf(second))
        assertEquals(listOf("2", "2", "false"), listOf("totalPages", "page", "hasNext").map { second.obj("meta").text(it) })

        val wrong = call(bearer, "/me/orders?filter=shipped")
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals(listOf("filter ORDER_FILTER_INVALID"), wrong.json().array("errors").map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" })
    }

    @Test
    fun `an order's page - lines, address, totals, its latest payment, the timeline from its events, and what is still to come`() = app {
        val orders = sarasOrders(1302)
        val bearer = login(phone(1302))

        val paid = call(bearer, "/me/orders/${orders.paid.value}")

        assertEquals(HttpStatusCode.OK, paid.status, paid.bodyAsText())
        val order = paid.json()
        assertEquals(listOf(orders.paid.value, "PROCESSING", "در حال آماده‌سازی"), listOf(order.text("code"), order.text("status"), order.text("statusLabel")))
        assertEquals(listOf("CURRENT", "UPCOMING", "UPCOMING", "UPCOMING"), order.obj("progress").array("steps").map { it.jsonObject.text("state") })

        val items = order.array("items").map { it.jsonObject }
        assertEquals(listOf("1", "2"), items.map { it.text("id") })
        val nila = items.single { it.text("variantId") == nilaOlive.toString() }
        assertEquals(listOf("کراس‌بادی نیلا", "olive", "1", "1450000", "1750000", "1450000"), listOf(
            nila.text("name"), nila.obj("color").text("key"), nila.text("quantity"), nila.text("unitPrice"), nila.text("unitCompareAtPrice"), nila.text("lineTotal"),
        ))
        assertEquals(JsonNull, nila["image"])
        assertFalse(nila["reviewed"]!!.jsonPrimitive.boolean)

        val address = order.obj("address")
        assertEquals("تهران، تهران، خیابان ولیعصر، کوچه نسترن، پلاک ۱۲، واحد ۴", address.text("fullText"))
        assertEquals(listOf("1965843117", "سارا محمدی", "09123456789"), listOf(address.text("postalCode"), address.text("recipientName"), address.text("recipientPhone")))

        val totals = order.obj("totals")
        assertEquals(order.text("payable"), totals.text("payable"))
        assertEquals(listOf("0", "0", "IRT"), listOf(totals.text("refunded"), totals.text("balanceDue"), totals.text("currency")))
        assertEquals("THRESHOLD", totals.text("shippingFreeReason"))

        val payment = order.obj("payment")
        assertEquals(listOf("ZARINPAL", "زرین‌پال", "SUCCEEDED", "6037-99**-****-1234"), listOf(payment.text("gateway"), payment.text("gatewayName"), payment.text("status"), payment.text("cardMask")))
        assertTrue(payment.text("referenceId")!!.isNotEmpty())

        assertEquals(
            listOf("PENDING_PAYMENT سفارش ثبت شد", "PROCESSING پرداخت شد"),
            order.array("timeline").map { it.jsonObject.let { e -> "${e.text("status")} ${e.text("label")}" } },
        )
        val photos = order.obj("preShipmentMediaDetail")
        assertEquals(listOf("WAITING", "BALE", "بله", "09121112233", "از داخل کیف هم عکس بگیرید"), listOf("status", "channel", "channelName", "phone", "note").map { photos.text(it) })
        assertEquals("قبل از ارسال، از کیفت عکس و ویدیو می‌گیریم و توی بله برات می‌فرستیم؛ یه نسخه هم همین‌جا قرار می‌گیره.", photos.text("waitingMessage"))
        assertEquals("بعد از تحویل به پست معمولی این‌جا نمایش داده می‌شه", order.obj("shipment").text("waitingMessage"))
        assertEquals("زنگ نزنید", order.text("customerNote"))
        assertEquals(listOf(JsonArray(emptyList()), JsonNull), listOf(order["returns"], order["invoiceUrl"]))

        val failed = call(bearer, "/me/orders/${orders.failed.value}").json()
        assertEquals(
            listOf("PENDING_PAYMENT سفارش ثبت شد null", "PAYMENT_FAILED پرداخت ناموفق تراکنش توسط کاربر لغو شد"),
            failed.array("timeline").map { it.jsonObject.let { e -> "${e.text("status")} ${e.text("label")} ${e.text("note")}" } },
        )
        assertEquals("CANCELLED", failed.obj("payment").text("status"))
        assertTrue(failed.obj("actions")["canPay"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `someone else's order, one that doesn't exist and a code that can't be one are all 404 ORDER_NOT_FOUND`() = app {
        val orders = sarasOrders(1303)
        val mina = login(phone(1304))

        listOf(orders.paid.value, "KV-000000", "abc", "kv-218340").forEach { code ->
            val response = call(mina, "/me/orders/$code")
            assertEquals(HttpStatusCode.NotFound, response.status, code)
            assertEquals("ORDER_NOT_FOUND", response.json().text("code"), code)
            val reorder = client.post("$API_V1/me/orders/$code/reorder") { header(HttpHeaders.Authorization, "Bearer $mina") }
            assertEquals(HttpStatusCode.NotFound, reorder.status, code)
        }
        val minas = call(mina, "/me/orders").json()
        assertEquals(emptyList(), minas.array("items"))
        assertEquals(0L, minas.obj("counts")["all"]!!.jsonPrimitive.long)
    }

    @Test
    fun `buying an expired order again - what is there goes into the cart, what isn't is skipped with why`() = app {
        val sara = purchase.customer(phone(1305))
        val (expired, payment) = place(sara, nilaOlive, aidaBlack, termehBlack)
        back(payment, paid = false)
        clock.advance(Duration.ofMinutes(16))
        expireDue()
        transaction(purchase.db) {
            // Aida black sold out, Termeh black taken out of the shop since.
            ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq aidaBlack }) { it[onHand] = 0 }
            ProductVariantQmTbl.update({ ProductVariantQmTbl.variantId eq termehBlack }) { it[isActive] = false }
        }
        val bearer = login(phone(1305))

        val response = client.post("$API_V1/me/orders/${expired.value}/reorder") { header(HttpHeaders.Authorization, "Bearer $bearer") }

        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        assertEquals(1, body["addedCount"]!!.jsonPrimitive.long)
        assertEquals("۱ کیف به سبد اضافه شد", body.text("message"))
        assertEquals(
            listOf("کیف دستی آیدا OUT_OF_STOCK", "کیف پول ترمه UNAVAILABLE"),
            body.array("skipped").map { it.jsonObject.let { s -> "${s.text("name")} ${s.text("reason")}" } },
        )
        assertEquals(listOf(nilaOlive.toString()), body.obj("cart").array("items").map { it.jsonObject.text("variantId") })
    }

    @Test
    fun `the panel's customers show what they bought - two paid orders counted, an expired one not`() {
        val sara = purchase.customer(phone(1306))
        val (_, expiredPayment) = place(sara, nilaOlive)
        back(expiredPayment, paid = false)
        clock.advance(Duration.ofMinutes(16))
        expireDue()
        val (first, firstPayment) = place(sara, nilaOlive)
        back(firstPayment, paid = true)
        val (second, secondPayment) = place(sara, nilaOlive, aidaBlack)
        back(secondPayment, paid = true)
        val (paid, placedOn) = transaction(purchase.db) {
            val orders = listOf(first, second).map { purchase.orderQm.findOfCustomer(sara, it)!! }
            orders.sumOf { it.totals.payable.toman } to orders.last().placedOn
        }

        val page = runBlocking {
            purchase.auth.findCustomers.execute(FindCustomersUc.Qry(Pageable.makeNew(1, 20).getOrNull()!!, q = phone(1306)))
        }.getOrNull()!!

        val customer = page.items.single()
        assertEquals(2, customer.ordersCount)
        assertEquals(paid, customer.totalSpent.toman)
        assertEquals(placedOn, customer.lastOrderAt)
    }
}
