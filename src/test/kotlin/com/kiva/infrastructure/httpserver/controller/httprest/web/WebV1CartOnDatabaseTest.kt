package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ApplyDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.RemoveDiscountCodeUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.CART_TOKEN_HEADER
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ReservationSwitch
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.StoredCatalog.NewColor
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.CartItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cart over the real stack on kiva_test: the prototype's 16 bags seeded as the dev seeder does it, guests with
 * their tokens and customers with tokens from a real login. Step 6 of the purchase roadmap is done when these pass.
 */
class WebV1CartOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val transactions = ExposedTransactionManager(stack.db)
    private val carts = CartRepositoryImpl()
    private val offers: OfferQmRepository = OfferQmRepositoryImpl()
    private val domain = CartDomainService(RandomCartIds())

    private fun commands(repository: CartRepository = carts) =
        CartCommands(transactions, repository, domain, offers, ShopSettingsRepositoryImpl(), stack.reservations, stack.discounts, clock)

    private val useCases = commands().let { c ->
        CartUseCases(
            OpenCartUc(c, domain),
            GetCartUc(transactions, carts, offers, ShopSettingsRepositoryImpl(), stack.reservations, stack.discounts, clock),
            AddCartItemUc(c, domain),
            ChangeCartItemUc(c, domain),
            RemoveCartItemUc(c, domain),
            ClearCartUc(c, domain),
            ChooseShippingMethodUc(c, domain),
            SetCartReservationUc(c, domain),
            MergeGuestCartUc(c, domain),
            ApplyDiscountCodeUc(c, domain, stack.discounts),
            RemoveDiscountCodeUc(c, domain),
        )
    }

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(stack.db) { CartTbl.deleteWhere { userId.isNull() } }
        stack.deleteTestUsers() // their carts go with them
        seeded.deleteSeeded()
        others.deleteAll()
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp(listOf(ORIGIN))
            configureAuthentication(stack.accessTokens)
            routing {
                route(API_V1) {
                    webV1Auth(stack.authUseCases, listOf(ORIGIN), testMediaUrls)
                    webV1Cart(useCases, testMediaUrls)
                }
            }
        }
        block()
    }

    // ============ helpers ============

    /** The variant id of a seeded bag's color. */
    private fun variantOf(slug: String, color: String): Long = transaction(stack.db) {
        ProductVariantQmTbl.join(ProductQmTbl, JoinType.INNER, ProductVariantQmTbl.productId, ProductQmTbl.productId)
            .selectAll()
            .where { (ProductQmTbl.slug eq slug) and (ProductVariantQmTbl.colorKey eq color) }
            .single()[ProductVariantQmTbl.variantId]
    }

    private val nilaOlive by lazy { variantOf("nila-crossbody-bag", "olive") }
    private val nilaBlack by lazy { variantOf("nila-crossbody-bag", "black") }
    private val aidaBlack by lazy { variantOf("aida-handbag", "black") }
    private val aidaCaramel by lazy { variantOf("aida-handbag", "caramel") }
    private val nazliBrown by lazy { variantOf("nazli-wallet", "brown") }
    private val mahakBlack by lazy { variantOf("mahak-shoulder-bag", "black") }
    private val shabtabBlack by lazy { variantOf("shabtab-clutch", "black") }
    private val mahtabWhite by lazy { variantOf("mahtab-clutch", "white") }

    private fun HttpRequestBuilder.who(token: String?, bearer: String?) {
        token?.let { header(CART_TOKEN_HEADER, it) }
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun ApplicationTestBuilder.getCart(token: String? = null, bearer: String? = null): HttpResponse =
        client.get("$API_V1/cart") { who(token, bearer) }

    private suspend fun ApplicationTestBuilder.send(
        method: String,
        path: String,
        body: String?,
        token: String?,
        bearer: String? = null,
    ): HttpResponse {
        val build: HttpRequestBuilder.() -> Unit = {
            who(token, bearer)
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return when (method) {
            "POST" -> client.post("$API_V1$path", build)
            "PATCH" -> client.patch("$API_V1$path", build)
            "PUT" -> client.put("$API_V1$path", build)
            else -> client.delete("$API_V1$path", build)
        }
    }

    private suspend fun ApplicationTestBuilder.add(variantId: Long, quantity: Int = 1, token: String? = null, bearer: String? = null) =
        send("POST", "/cart/items", """{"variantId":$variantId,"quantity":$quantity}""", token, bearer)

    /** A guest's new cart: its token, from the first answer's header. */
    private suspend fun ApplicationTestBuilder.newGuest(): String = getCart().headers[CART_TOKEN_HEADER]!!

    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The cart of an answer: the body itself, or its `cart` for a line's change. */
    private suspend fun HttpResponse.cart(): JsonObject = json().let { it["cart"]?.jsonObject ?: it }

    private suspend fun HttpResponse.code(): String = json()["code"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]!!.jsonArray.map { "${it.jsonObject["field"]!!.jsonPrimitive.content} ${it.jsonObject["code"]!!.jsonPrimitive.content}" }

    private fun JsonObject.items(): List<JsonObject> = this["items"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.long(name: String): Long = this[name]!!.jsonPrimitive.long

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    /** Runs [work] on [n] threads released together, so the database really sees the calls at the same moment. */
    private fun <T> atOnce(n: Int, work: suspend (Int) -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val calls = List(n) { index -> pool.submit<T> { start.await(); runBlocking { work(index) } } }
            start.countDown()
            calls.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    // ============ step 6 is done when ============

    @Test
    fun `a guest without a token gets an empty cart and its token, the same token the same cart, an unknown one a new cart`() = app {
        val first = getCart()
        assertEquals(HttpStatusCode.OK, first.status)
        val token = assertNotNull(first.headers[CART_TOKEN_HEADER])
        assertTrue(Regex("gct_[A-Za-z0-9_-]{43}").matches(token))
        assertEquals(emptyList(), first.cart().items())
        assertTrue(first.cart()["isGuest"]!!.jsonPrimitive.boolean)

        val again = getCart(token)
        assertEquals(first.cart().text("id"), again.cart().text("id"))
        assertNull(again.headers[CART_TOKEN_HEADER])

        val unknown = getCart("gct_" + "A".repeat(43))
        val malformed = getCart("not-a-token")
        listOf(unknown, malformed).forEach {
            assertNotEquals(first.cart().text("id"), it.cart().text("id"))
            assertNotNull(it.headers[CART_TOKEN_HEADER])
        }
    }

    @Test
    fun `a customer gets their own cart and the guest token is ignored, an expired login is 401 and never a guest`() = app {
        val guestToken = newGuest()
        add(nilaOlive, token = guestToken)
        val bearer = login(phone(601))

        val mine = getCart(guestToken, bearer)
        assertEquals(HttpStatusCode.OK, mine.status)
        assertFalse(mine.cart()["isGuest"]!!.jsonPrimitive.boolean)
        assertFalse(mine.cart()["checkoutRequiresLogin"]!!.jsonPrimitive.boolean)
        assertEquals(emptyList(), mine.cart().items())
        assertNull(mine.headers[CART_TOKEN_HEADER])
        assertEquals(1, getCart(guestToken).cart().items().size) // the guest's cart is untouched

        clock.advance(Duration.ofMinutes(16))
        val expired = getCart(guestToken, bearer)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals("TOKEN_EXPIRED", expired.code())
    }

    @Test
    fun `adding - sold out, unknown, a draft and a wrong quantity are refused`() = app {
        val token = newGuest()
        val draft = others.newProduct(listOf(NewColor("lilac"))).variants.single().id.value

        val soldOut = add(shabtabBlack, token = token)
        assertEquals(HttpStatusCode.UnprocessableEntity, soldOut.status)
        assertEquals("OUT_OF_STOCK", soldOut.code())

        val unknown = add(987_654_321, token = token)
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals("VARIANT_NOT_FOUND", unknown.code())

        val notInShop = add(draft, token = token)
        assertEquals(HttpStatusCode.UnprocessableEntity, notInShop.status)
        assertEquals("PRODUCT_UNAVAILABLE", notInShop.code())

        listOf(0, 21).forEach {
            val wrong = add(nilaOlive, quantity = it, token = token)
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals(listOf("quantity QUANTITY_INVALID"), wrong.fieldErrors())
        }
        assertEquals(emptyList(), getCart(token).cart().items())
    }

    @Test
    fun `adding answers with the line it touched and a toast, and the totals of the design`() = app {
        val token = newGuest()

        val added = add(nilaOlive, token = token)
        assertEquals(HttpStatusCode.OK, added.status)
        val body = added.json()
        val line = body["cart"]!!.jsonObject.items().single()
        assertEquals(line.text("id"), body.text("affectedItemId"))
        assertEquals("کراس‌بادی نیلا — زیتونی (۱ عدد) به سبد اضافه شد", body.text("message"))
        assertEquals(JsonNull, body["removedItem"])

        val cart = body["cart"]!!.jsonObject
        val totals = cart["totals"]!!.jsonObject
        assertEquals(1_525_000, totals.long("payable"))
        assertEquals("IRT", totals.text("currency"))
        val free = cart["freeShipping"]!!.jsonObject
        assertEquals(1_550_000, free.long("remaining"))
        assertEquals("48.3", free["progressPercent"]!!.jsonPrimitive.content)
        assertEquals("فقط ۱٬۵۵۰٬۰۰۰ تومان تا ارسال رایگان با پست معمولی", free.text("message"))
        assertEquals("/product/nila-crossbody-bag?color=olive", line["product"]!!.jsonObject.text("url"))
        assertEquals("زیتونی", line["color"]!!.jsonObject.text("name"))
    }

    @Test
    fun `the first golden cart - Aida caramel and the Nazli wallet ship free by post, 3,480,000`() = app {
        val token = newGuest()
        add(aidaCaramel, token = token)
        val totals = add(nazliBrown, token = token).cart()["totals"]!!.jsonObject

        assertEquals(4_080_000, totals.long("itemsCompareAtTotal"))
        assertEquals(600_000, totals.long("productDiscount"))
        assertEquals(3_480_000, totals.long("subtotal"))
        assertEquals(0, totals.long("shippingCost"))
        assertTrue(totals["shippingIsFree"]!!.jsonPrimitive.boolean)
        assertEquals(3_480_000, totals.long("payable"))
    }

    @Test
    fun `changing a line - beyond the stock or the product's limit is refused with the line's maxQuantity`() = app {
        val token = newGuest()
        val aida = add(aidaBlack, token = token).json().text("affectedItemId")!!
        val nila = add(nilaOlive, token = token).json().text("affectedItemId")!!

        val beyondStock = send("PATCH", "/cart/items/$aida", """{"quantity":4}""", token)
        assertEquals(HttpStatusCode.UnprocessableEntity, beyondStock.status)
        assertEquals("QUANTITY_EXCEEDS_STOCK", beyondStock.code())
        assertEquals(3, beyondStock.json()["meta"]!!.jsonObject["availableQuantity"]!!.jsonPrimitive.int)

        val beyondLimit = send("PATCH", "/cart/items/$nila", """{"quantity":6}""", token)
        assertEquals(HttpStatusCode.UnprocessableEntity, beyondLimit.status)
        assertEquals("QUANTITY_EXCEEDS_LIMIT", beyondLimit.code())
        assertEquals(5, beyondLimit.json()["meta"]!!.jsonObject["maxQuantity"]!!.jsonPrimitive.int)

        val changed = send("PATCH", "/cart/items/$nila", """{"quantity":2,"variantId":$nilaBlack}""", token)
        assertEquals(HttpStatusCode.OK, changed.status)
        val line = changed.cart().items().single { it.text("id") == nila }
        assertEquals(nilaBlack, line.long("variantId"))
        assertEquals(2, line["quantity"]!!.jsonPrimitive.int)

        assertEquals(listOf("quantity QUANTITY_OR_VARIANT_REQUIRED"), send("PATCH", "/cart/items/$nila", "{}", token).fieldErrors())
        assertEquals(listOf("variantId VARIANT_ID_INVALID"), send("PATCH", "/cart/items/$nila", """{"variantId":$aidaCaramel}""", token).fieldErrors())
        assertEquals("CART_ITEM_NOT_FOUND", send("PATCH", "/cart/items/abc", """{"quantity":1}""", token).code())
    }

    @Test
    fun `removing gives the line back for «برگردون», and a line of another cart is not found`() = app {
        val token = newGuest()
        val other = newGuest()
        val itemId = send("POST", "/cart/items", """{"variantId":$nilaOlive,"quantity":2}""", token).json().text("affectedItemId")!!

        val stranger = send("DELETE", "/cart/items/$itemId", null, other)
        assertEquals(HttpStatusCode.NotFound, stranger.status)
        assertEquals("CART_ITEM_NOT_FOUND", stranger.code())

        val removed = send("DELETE", "/cart/items/$itemId", null, token)
        assertEquals(HttpStatusCode.OK, removed.status)
        val body = removed.json()
        assertEquals(nilaOlive, body["removedItem"]!!.jsonObject.long("variantId"))
        assertEquals(2, body["removedItem"]!!.jsonObject["quantity"]!!.jsonPrimitive.int)
        assertEquals("کراس‌بادی نیلا", body["removedItem"]!!.jsonObject.text("name"))
        assertEquals("کراس‌بادی نیلا — زیتونی از سبد حذف شد", body.text("message"))
        assertEquals(emptyList(), body["cart"]!!.jsonObject.items())
    }

    @Test
    fun `shipping by tipax costs 145,000 and is selected, courier is not offered, a bike is not a method`() = app {
        val token = newGuest()
        add(nilaOlive, token = token)

        val tipax = send("PUT", "/cart/shipping-method", """{"method":"TIPAX"}""", token)
        assertEquals(HttpStatusCode.OK, tipax.status)
        val cart = tipax.json()
        assertEquals("TIPAX", cart.text("shippingMethod"))
        val options = cart["shippingOptions"]!!.jsonArray.map { it.jsonObject }.associateBy { it.text("method") }
        assertEquals(setOf("TIPAX", "POST"), options.keys)
        assertTrue(options.getValue("TIPAX")["selected"]!!.jsonPrimitive.boolean)
        assertFalse(options.getValue("POST")["selected"]!!.jsonPrimitive.boolean)
        assertEquals("تحویل ۱ تا ۲ روز کاری", options.getValue("TIPAX").text("description"))
        assertEquals(145_000, cart["totals"]!!.jsonObject.long("shippingCost"))

        val courier = send("PUT", "/cart/shipping-method", """{"method":"COURIER"}""", token)
        assertEquals(HttpStatusCode.UnprocessableEntity, courier.status)
        assertEquals("SHIPPING_METHOD_UNAVAILABLE", courier.code())

        val bike = send("PUT", "/cart/shipping-method", """{"method":"BIKE"}""", token)
        assertEquals(HttpStatusCode.BadRequest, bike.status)
        assertEquals(listOf("method SHIPPING_METHOD_INVALID"), bike.fieldErrors())
    }

    @Test
    fun `«رزرو ۴ روزه» switches on, with its three cards in Tehran's Jalali dates`() = app {
        val token = newGuest()
        add(nilaOlive, token = token)

        val on = send("PUT", "/cart/reservation", """{"enabled":true}""", token)

        assertEquals(HttpStatusCode.OK, on.status)
        val reservation = on.json()["reservation"]!!.jsonObject
        assertEquals(true, reservation["available"]!!.jsonPrimitive.boolean)
        assertEquals(true, reservation["enabled"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, reservation["unavailableReason"])
        assertEquals("2026-10-16", reservation.text("shipAfterDate"))
        // Monday 2026-10-12 is ۲۰ مهر ۱۴۰۵; four days on, Friday ۲۴ مهر.
        assertEquals(
            listOf(
                "امروز، ۲۰ مهر" to "پرداخت کامل و ثبت سفارش",
                "تا جمعه ۲۴ مهر" to "هر محصول دیگه‌ای خواستی به همین سفارش اضافه کن",
                "بعد از ۴ روز" to "همه با هم و با یک هزینه ارسال فرستاده می‌شن",
            ),
            reservation["timeline"]!!.jsonArray.map { it.jsonObject.text("title") to it.jsonObject.text("text") },
        )
        assertEquals(JsonNull, on.json()["consolidation"], "a guest holds no reservation")
    }

    @Test
    fun `the party clutch can't be held - ITEM_NOT_RESERVABLE, and switching on is refused`() = app {
        val token = newGuest()
        add(mahtabWhite, token = token)

        val cart = getCart(token).json()["reservation"]!!.jsonObject
        val on = send("PUT", "/cart/reservation", """{"enabled":true}""", token)

        assertEquals(false, cart["available"]!!.jsonPrimitive.boolean)
        assertEquals("ITEM_NOT_RESERVABLE", cart.text("unavailableReason"))
        assertEquals(HttpStatusCode.UnprocessableEntity, on.status)
        assertEquals("RESERVATION_UNAVAILABLE", on.code())
        assertEquals("ITEM_NOT_RESERVABLE", on.json()["meta"]!!.jsonObject.text("reason"))
    }

    @Test
    fun `with reservations switched off in the shop, switching on is RESERVATION_DISABLED, off is always fine, and no cards`() = app {
        val token = newGuest()

        ReservationSwitch.with(stack.db, enabled = false) {
            val on = send("PUT", "/cart/reservation", """{"enabled":true}""", token)
            assertEquals(HttpStatusCode.UnprocessableEntity, on.status)
            assertEquals("RESERVATION_UNAVAILABLE", on.code())
            assertEquals("RESERVATION_DISABLED", on.json()["meta"]!!.jsonObject.text("reason"))

            val off = send("PUT", "/cart/reservation", """{"enabled":false}""", token)
            assertEquals(HttpStatusCode.OK, off.status)
            val reservation = off.json()["reservation"]!!.jsonObject
            assertEquals("RESERVATION_DISABLED", reservation.text("unavailableReason"))
            assertEquals(4, reservation["holdDays"]!!.jsonPrimitive.int)
            assertEquals("2026-10-16", reservation.text("shipAfterDate"))
            assertEquals(JsonArray(emptyList()), reservation["timeline"])
        }
    }

    @Test
    fun `clearing empties the cart and keeps the shipping method`() = app {
        val token = newGuest()
        add(nilaOlive, token = token)
        send("PUT", "/cart/shipping-method", """{"method":"TIPAX"}""", token)

        val cleared = send("DELETE", "/cart", null, token)

        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(emptyList(), cleared.json().items())
        assertEquals("TIPAX", cleared.json().text("shippingMethod"))
    }

    @Test
    fun `every answer has the whole cart, its empty fields null, never left out`() = app {
        val token = newGuest()
        val cart = add(nilaOlive, token = token).cart()
        val line = cart.items().single()

        assertEquals(
            setOf(
                "id", "isGuest", "items", "totals", "shippingMethod", "shippingOptions", "freeShipping", "reservation", "consolidation",
                "discount", "issues", "checkoutRequiresLogin", "updatedAt",
            ),
            cart.keys,
        )
        assertEquals(
            setOf(
                "id", "productId", "variantId", "product", "color", "image", "quantity", "maxQuantity", "purchasable", "unitPrice",
                "unitCompareAtPrice", "lineTotal", "lineCompareAtTotal", "lineDiscount", "stock", "isWishlisted", "addedAt",
            ),
            line.keys,
        )
        listOf(cart["consolidation"], cart["discount"], line["image"], line["isWishlisted"]).forEach { assertEquals(JsonNull, it) }
        assertTrue(line["purchasable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `no exact stock above 4 is ever shown - 12 left is a limit of 5 and no number`() = app {
        val token = newGuest()

        val line = add(mahakBlack, token = token).cart().items().single()

        assertEquals(5, line["maxQuantity"]!!.jsonPrimitive.int)
        val stock = line["stock"]!!.jsonObject
        assertEquals("IN_STOCK", stock.text("status"))
        assertEquals(JsonNull, stock["availableQuantity"])
        assertEquals(5, stock["maxOrderQuantity"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a change in the shop is told once - less left cuts the line, and the next look says nothing`() = app {
        val token = newGuest()
        val itemId = send("POST", "/cart/items", """{"variantId":$aidaBlack,"quantity":3}""", token).json().text("affectedItemId")
        others.stock(VariantId.constructFromPersisted(aidaBlack)) {
            it.adjust(StockDelta.constructFromPersisted(-1), AdjustmentReason.DAMAGED, null, others.staff, clock.now)
        }

        val issues = getCart(token).json()["issues"]!!.jsonArray.map { it.jsonObject }

        assertEquals(1, issues.size)
        assertEquals("QUANTITY_REDUCED", issues.single().text("code"))
        assertEquals(itemId, issues.single().text("itemId"))
        assertEquals(3, issues.single().long("previousValue"))
        assertEquals(2, issues.single().long("currentValue"))
        assertEquals("تعداد «کیف دستی آیدا — مشکی» به ۲ عدد کم شد؛ بیشتر از این نمی‌شه خرید.", issues.single().text("message"))
        assertEquals(2, getCart(token).cart().items().single()["quantity"]!!.jsonPrimitive.int)
        assertEquals(JsonArray(emptyList()), getCart(token).json()["issues"])
    }

    @Test
    fun `only the token's hash is stored, never the token`() = app {
        val first = getCart()
        val token = first.headers[CART_TOKEN_HEADER]!!
        val cartId = first.cart().text("id")!!

        val row = transaction(stack.db) { CartTbl.selectAll().where { CartTbl.publicId eq cartId }.single() }

        assertContentEquals(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()), row[CartTbl.guestTokenHash])
        assertFalse(listOf(row[CartTbl.publicId], row[CartTbl.discountCode].orEmpty()).any { it.contains(token.removePrefix("gct_")) })
        assertEquals(TokenHash.LENGTH, row[CartTbl.guestTokenHash]!!.size)
    }

    @Test
    fun `20 adds at once on one cart - each product ends at its limit exactly, one line per color`() = app {
        val token = newGuest()
        // Each call holds the cart a moment after locking it, so without the lock they would surely overlap.
        val slowCarts = object : CartRepository by carts {
            override fun lockForGuest(tokenHash: TokenHash) = carts.lockForGuest(tokenHash).also { Thread.sleep(30) }
        }
        val addSlowly = AddCartItemUc(commands(slowCarts), domain)
        val colors = listOf(nilaOlive, mahakBlack, nazliBrown, variantOf("sora-backpack", "black"))

        val results = atOnce(20) { addSlowly.execute(AddCartItemUc.Cmd(CartCaller.Guest(token), colors[it % 4], 1)) }

        assertTrue(results.all { it.isSuccess }, "$results")
        val lines = transaction(stack.db) {
            val cartId = CartTbl.selectAll().where { CartTbl.guestTokenHash eq TokenHash(MessageDigest.getInstance("SHA-256").digest(token.toByteArray())).toByteArray() }
                .single()[CartTbl.id].value
            CartItemTbl.selectAll().where { CartItemTbl.cartId eq cartId }.map { it[CartItemTbl.variantId] to it[CartItemTbl.quantity] }
        }
        assertEquals(colors.associateWith { 5 }, lines.toMap())
        assertEquals(4, lines.size)
    }

    @Test
    fun `a customer's first requests at once make one cart`() = app {
        val user: UserId = transaction(stack.db) {
            UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted(phone(602)), clock.now).user.id
        }
        val openSlowly = OpenCartUc(
            commands(object : CartRepository by carts {
                override fun lockForUser(userId: UserId) = carts.lockForUser(userId).also { Thread.sleep(30) }
            }),
            domain,
        )

        val opened = atOnce(10) { openSlowly.execute(OpenCartUc.Cmd(CartCaller.Customer(user))).getOrNull()!!.cartId }

        assertEquals(1, opened.toSet().size)
        assertEquals(1, transaction(stack.db) { CartTbl.selectAll().where { CartTbl.userId eq user.value }.count() })
    }
}
