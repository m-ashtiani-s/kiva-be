package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.cart.model.valueobject.CartToken
import com.kiva.domain.aggregate.cart.repository.CartRepository
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.otp.model.valueobject.OtpCode
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.session.model.valueobject.TokenHash
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.CART_TOKEN_HEADER
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.CartItemTbl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.seed.SeededCatalog
import io.ktor.client.request.HttpRequestBuilder
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
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Merging a guest's cart after a login, over the real stack on kiva_test: `POST /auth/otp/verify` with
 * `guestCartToken`, and `POST /cart/merge`. The prototype's bags are seeded as the dev seeder does it; customers log in
 * with real codes. Step 7 of the purchase roadmap is done when these pass.
 */
class WebV1CartMergeOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2037-03-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val transactions = ExposedTransactionManager(stack.db)
    private val carts = CartRepositoryImpl()
    private val domain = stack.cartDomainService

    private fun commands(repository: CartRepository = carts) =
        CartCommands(transactions, repository, domain, OfferQmRepositoryImpl(), ShopSettingsRepositoryImpl(), clock)

    private val cartUseCases = commands().let { c ->
        CartUseCases(
            OpenCartUc(c, domain),
            stack.getCart,
            AddCartItemUc(c, domain),
            ChangeCartItemUc(c, domain),
            RemoveCartItemUc(c, domain),
            ClearCartUc(c, domain),
            ChooseShippingMethodUc(c, domain),
            SetCartReservationUc(c, domain),
            MergeGuestCartUc(c, domain),
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
                    webV1Auth(stack.authUseCases, listOf(ORIGIN))
                    webV1Cart(cartUseCases)
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
    private val aidaBlack by lazy { variantOf("aida-handbag", "black") }
    private val aidaCaramel by lazy { variantOf("aida-handbag", "caramel") }
    private val nazliBrown by lazy { variantOf("nazli-wallet", "brown") }

    private fun HttpRequestBuilder.who(token: String?, bearer: String?) {
        token?.let { header(CART_TOKEN_HEADER, it) }
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private suspend fun ApplicationTestBuilder.getCart(token: String? = null, bearer: String? = null): HttpResponse =
        client.get("$API_V1/cart") { who(token, bearer) }

    private suspend fun ApplicationTestBuilder.add(variantId: Long, quantity: Int = 1, token: String? = null, bearer: String? = null): HttpResponse =
        client.post("$API_V1/cart/items") {
            who(token, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":$quantity}""")
        }

    private suspend fun ApplicationTestBuilder.choose(method: String, token: String? = null, bearer: String? = null): HttpResponse =
        client.put("$API_V1/cart/shipping-method") {
            who(token, bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"method":"$method"}""")
        }

    private suspend fun ApplicationTestBuilder.mergeWithBody(body: String, bearer: String?): HttpResponse =
        client.post("$API_V1/cart/merge") {
            who(token = null, bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.merge(guestCartToken: String, bearer: String?): HttpResponse =
        mergeWithBody("""{"guestCartToken":"$guestCartToken"}""", bearer)

    /** A guest's new cart: its token, from the first answer's header. */
    private suspend fun ApplicationTestBuilder.newGuest(): String = getCart().headers[CART_TOKEN_HEADER]!!

    /** Sends a login code; a phone gets a new one only two minutes after its last, so the clock moves on first. */
    private suspend fun ApplicationTestBuilder.sendCode(phone: String): OtpCode {
        clock.advance(Duration.ofSeconds(121))
        val sent = client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        return stack.lastCodeSentTo(phone)
    }

    private suspend fun ApplicationTestBuilder.verify(phone: String, code: String, guestCartToken: String? = null): HttpResponse =
        client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            val token = guestCartToken?.let { ""","guestCartToken":"$it"""" }.orEmpty()
            setBody("""{"phone":"$phone","code":"$code"$token}""")
        }

    private suspend fun ApplicationTestBuilder.login(phone: String, guestCartToken: String? = null): HttpResponse =
        verify(phone, sendCode(phone).value, guestCartToken).also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }

    private suspend fun HttpResponse.bearer(): String = json()["accessToken"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** The cart of an answer: a login's `cart`, a line change's `cart`, or the body itself. */
    private suspend fun HttpResponse.cart(): JsonObject = json().let { it["cart"]?.jsonObject ?: it }

    private fun JsonObject.items(): List<JsonObject> = this["items"]!!.jsonArray.map { it.jsonObject }

    /** Variant to quantity, oldest line first. */
    private fun JsonObject.quantities(): List<Pair<Long, Int>> = items().map { it["variantId"]!!.jsonPrimitive.long to it["quantity"]!!.jsonPrimitive.int }

    private fun JsonObject.lineIdOf(variantId: Long): String = items().single { it["variantId"]!!.jsonPrimitive.long == variantId }.text("id")!!

    private fun JsonObject.issues(): List<JsonObject> = this["issues"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun cartRowExists(publicId: String): Boolean = transaction(stack.db) { CartTbl.selectAll().where { CartTbl.publicId eq publicId }.count() > 0 }

    private fun registered(phone: String): UserId = transaction(stack.db) {
        UserRepositoryImpl().findOrRegister(IranianMobileNumber.constructFromPersisted(phone), clock.now).user.id
    }

    /** The lines in [user]'s cart, as variant to quantity. */
    private fun linesOf(user: UserId): Map<Long, Int> = transaction(stack.db) {
        val cartId = CartTbl.selectAll().where { CartTbl.userId eq user.value }.singleOrNull()?.get(CartTbl.id)?.value ?: return@transaction emptyMap()
        CartItemTbl.selectAll().where { CartItemTbl.cartId eq cartId }.associate { it[CartItemTbl.variantId] to it[CartItemTbl.quantity] }
    }

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

    /** Each merge holds the guest cart a moment after locking it, so without the lock two merges would surely overlap. */
    private fun slowMerge(): MergeGuestCartUc = MergeGuestCartUc(
        commands(object : CartRepository by carts {
            override fun lockForGuest(tokenHash: TokenHash) = carts.lockForGuest(tokenHash).also { Thread.sleep(50) }
        }),
        domain,
    )

    // ============ the login ============

    @Test
    fun `a login with the guest's token answers with the merged cart, the guest's lines keeping their ids, and the guest cart is gone`() = app {
        val phone = phone(701)
        add(aidaBlack, bearer = login(phone).bearer())
        clock.advance(Duration.ofMinutes(1)) // the guest's lines are the newer ones
        val guest = newGuest()
        val guestCart = add(nilaOlive, 2, token = guest).cart()
        val guestCartId = guestCart.text("id")!!
        clock.advance(Duration.ofMinutes(1))
        val guestIds = listOf(guestCart.lineIdOf(nilaOlive), add(nazliBrown, token = guest).cart().lineIdOf(nazliBrown))

        val answer = login(phone, guest)

        val cart = answer.cart()
        assertFalse(cart["isGuest"]!!.jsonPrimitive.boolean)
        assertEquals(listOf(aidaBlack to 1, nilaOlive to 2, nazliBrown to 1), cart.quantities())
        assertEquals(guestIds, listOf(cart.lineIdOf(nilaOlive), cart.lineIdOf(nazliBrown)))
        assertEquals(JsonArray(emptyList()), cart["issues"])
        assertNull(answer.headers[CART_TOKEN_HEADER])
        assertEquals(cart.text("id"), getCart(bearer = answer.bearer()).cart().text("id"))

        assertFalse(cartRowExists(guestCartId))
        val afterwards = getCart(guest) // the old token reaches nothing now: a new, empty cart
        assertNotEquals(guestCartId, afterwards.cart().text("id"))
        assertEquals(emptyList(), afterwards.cart().items())
        assertNotNull(afterwards.headers[CART_TOKEN_HEADER])
    }

    @Test
    fun `a color in both carts becomes the customer's line, and the product's limit is told in the login's answer, once`() = app {
        val phone = phone(702)
        val bearer = login(phone).bearer()
        val customersBlack = add(aidaBlack, 2, bearer = bearer).cart().lineIdOf(aidaBlack)
        clock.advance(Duration.ofMinutes(1)) // the guest's lines are the newer ones
        val guest = newGuest()
        add(aidaBlack, 1, token = guest)
        val guestsCaramel = add(aidaCaramel, 3, token = guest).cart().lineIdOf(aidaCaramel)

        val answer = login(phone, guest)

        // Aida's limit is 5: the customer's older black keeps 3, the guest's caramel gets what is left.
        val cart = answer.cart()
        assertEquals(listOf(aidaBlack to 3, aidaCaramel to 2), cart.quantities())
        assertEquals(customersBlack, cart.lineIdOf(aidaBlack))
        val issue = cart.issues().single()
        assertEquals(listOf("QUANTITY_REDUCED", guestsCaramel, "3", "2"), listOf("code", "itemId", "previousValue", "currentValue").map { issue.text(it) })
        assertEquals("تعداد «کیف دستی آیدا — کاراملی» به ۲ عدد کم شد؛ بیشتر از این نمی‌شه خرید.", issue.text("message"))
        assertEquals(emptyList(), getCart(bearer = answer.bearer()).cart().issues())
    }

    @Test
    fun `the guest's shipping method wins when the guest cart holds something`() = app {
        val phone = phone(703)
        add(aidaBlack, bearer = login(phone).bearer())
        val guest = newGuest()
        add(nilaOlive, token = guest)
        choose("TIPAX", token = guest)

        assertEquals("TIPAX", login(phone, guest).cart().text("shippingMethod"))
    }

    @Test
    fun `an empty guest cart, made by a mere visit, changes nothing and is deleted`() = app {
        val phone = phone(704)
        val bearer = login(phone).bearer()
        add(aidaBlack, bearer = bearer)
        choose("TIPAX", bearer = bearer)
        val guest = newGuest()
        val guestCartId = getCart(guest).cart().text("id")!!

        val cart = login(phone, guest).cart()

        assertEquals("TIPAX", cart.text("shippingMethod"))
        assertEquals(listOf(aidaBlack to 1), cart.quantities())
        assertFalse(cartRowExists(guestCartId))
    }

    @Test
    fun `a login without a token answers with the customer's own cart, brought up to date and told once`() = app {
        val phone = phone(705)
        val first = login(phone).cart()
        assertEquals(emptyList(), first.items())
        assertFalse(first["isGuest"]!!.jsonPrimitive.boolean)
        add(aidaBlack, 3, bearer = login(phone).bearer())
        others.stock(VariantId.constructFromPersisted(aidaBlack)) {
            it.adjust(StockDelta.constructFromPersisted(-1), AdjustmentReason.DAMAGED, null, others.staff, clock.now)
        }

        val answer = login(phone)

        val cart = answer.cart()
        assertEquals(first.text("id"), cart.text("id"))
        assertEquals(listOf(aidaBlack to 2), cart.quantities())
        assertEquals(listOf("QUANTITY_REDUCED"), cart.issues().map { it.text("code") })
        assertEquals(emptyList(), getCart(bearer = answer.bearer()).cart().issues())
    }

    @Test
    fun `a wrong code merges nothing - the guest cart stays as it was`() = app {
        val phone = phone(706)
        val bearer = login(phone).bearer()
        val guest = newGuest()
        val guestCart = add(nilaOlive, 2, token = guest).cart()
        val right = sendCode(phone)

        val wrong = verify(phone, ((right.value.toInt() + 1) % 100_000).toString().padStart(5, '0'), guest)

        assertEquals(HttpStatusCode.UnprocessableEntity, wrong.status)
        assertEquals("OTP_INVALID", wrong.json().text("code"))
        val stillThere = getCart(guest).cart()
        assertEquals(guestCart.text("id"), stillThere.text("id"))
        assertEquals(listOf(nilaOlive to 2), stillThere.quantities())
        assertEquals(emptyList(), getCart(bearer = bearer).cart().items())
    }

    @Test
    fun `a malformed or unknown token never spoils a login`() = app {
        val phone = phone(707)

        listOf("abc", "gct_" + "A".repeat(43), "").forEach { token ->
            val answer = login(phone, token)
            assertEquals(emptyList(), answer.cart().items())
        }
    }

    // ============ POST /cart/merge ============

    @Test
    fun `merging needs a login - without one, or with an expired one, it is 401 and the guest cart stays`() = app {
        val bearer = login(phone(708)).bearer()
        val guest = newGuest()
        add(nilaOlive, token = guest)

        val noLogin = merge(guest, bearer = null)
        clock.advance(Duration.ofMinutes(16))
        val expired = merge(guest, bearer)

        assertEquals(HttpStatusCode.Unauthorized, noLogin.status)
        assertEquals(HttpStatusCode.Unauthorized, expired.status)
        assertEquals("TOKEN_EXPIRED", expired.json().text("code"))
        assertEquals(listOf(nilaOlive to 1), getCart(guest).cart().quantities())
    }

    @Test
    fun `POST cart merge merges like a login and answers with the cart itself`() = app {
        val login = login(phone(709))
        val bearer = login.bearer()
        val guest = newGuest()
        val guestCart = add(nilaOlive, 2, token = guest).cart()

        val merged = merge(guest, bearer)

        assertEquals(HttpStatusCode.OK, merged.status, merged.bodyAsText())
        val cart = merged.json()
        assertEquals(login.cart().text("id"), cart.text("id"))
        assertEquals(listOf(nilaOlive to 2), cart.quantities())
        assertEquals(guestCart.lineIdOf(nilaOlive), cart.lineIdOf(nilaOlive))
        assertNull(merged.headers[CART_TOKEN_HEADER])
        assertFalse(cartRowExists(guestCart.text("id")!!))
    }

    @Test
    fun `a token that reaches no cart - merged already, unknown or malformed - is 200 with the customer's cart unchanged`() = app {
        val bearer = login(phone(710)).bearer()
        val guest = newGuest()
        add(nilaOlive, 2, token = guest)
        val cart = merge(guest, bearer).json()

        listOf(guest, "gct_" + "A".repeat(43), "abc", "").forEach { token ->
            val again = merge(token, bearer)
            assertEquals(HttpStatusCode.OK, again.status, token)
            assertEquals(cart.text("id"), again.json().text("id"))
            assertEquals(listOf(nilaOlive to 2), again.json().quantities())
        }
    }

    @Test
    fun `a merge without its token is 400`() = app {
        val bearer = login(phone(711)).bearer()

        assertEquals(HttpStatusCode.BadRequest, mergeWithBody("{}", bearer).status)
    }

    @Test
    fun `no token reaches a customer's cart - not one made of their cart's id, nor one another customer merged`() = app {
        val sara = phone(712)
        val mina = phone(713)
        val saraBearer = login(sara).bearer()
        val saraCart = add(aidaBlack, 2, bearer = saraBearer).cart()
        val minaBearer = login(mina).bearer()
        val saraId = saraCart.text("id")!!
        val handMade = listOf(
            saraId,
            "gct_" + saraId.padEnd(43, 'A'),
            "gct_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)),
        )

        handMade.forEach { assertEquals(emptyList(), merge(it, minaBearer).json().items(), it) }

        val guest = newGuest()
        add(nilaOlive, token = guest)
        merge(guest, saraBearer)
        assertEquals(emptyList(), merge(guest, minaBearer).json().items())

        assertEquals(listOf(aidaBlack to 2, nilaOlive to 1), getCart(bearer = saraBearer).cart().quantities())
        assertEquals(emptyList(), getCart(bearer = minaBearer).cart().items())
    }

    // ============ at the same moment ============

    @Test
    fun `a guest cart merged by two customers at once goes into one cart only`() = app {
        val guest = newGuest()
        add(nilaOlive, 2, token = guest)
        add(nazliBrown, token = guest)
        val customers = listOf(registered(phone(714)), registered(phone(715)))
        val merge = slowMerge()

        val results = atOnce(2) { merge.execute(MergeGuestCartUc.Cmd(customers[it], guest)) }

        assertTrue(results.all { it.isSuccess }, "$results")
        val lines = customers.map(::linesOf)
        assertEquals(listOf(mapOf(nilaOlive to 2, nazliBrown to 1), emptyMap()), lines.sortedByDescending { it.size })
        assertNull(transaction(stack.db) { carts.lockForGuest(CartToken.makeNew(guest).getOrNull()!!.hash()) })
    }

    @Test
    fun `two merges of one guest cart by the same customer at once add its lines once`() = app {
        val guest = newGuest()
        add(nilaOlive, 2, token = guest)
        val customer = registered(phone(716))
        val merge = slowMerge()

        val results = atOnce(2) { merge.execute(MergeGuestCartUc.Cmd(customer, guest)) }

        assertTrue(results.all { it.isSuccess }, "$results")
        assertEquals(1, results.map { it.getOrNull()!!.cartId }.toSet().size)
        assertEquals(mapOf(nilaOlive to 2), linesOf(customer))
    }

    @Test
    fun `a merge command never prints its token`() {
        val token = "gct_" + "Q".repeat(43)

        assertFalse(token in "${MergeGuestCartUc.Cmd(UserId.constructFromPersisted(7), token)}")
    }
}
