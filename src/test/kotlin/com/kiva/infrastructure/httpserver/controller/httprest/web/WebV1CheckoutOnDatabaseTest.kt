package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.PaymentGateways
import com.kiva.application.repository.OfferQmRepository
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.application.usecase.cart.CartCommands
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChangeCartItemUc
import com.kiva.application.usecase.cart.usecase.command.ChooseShippingMethodUc
import com.kiva.application.usecase.cart.usecase.command.ClearCartUc
import com.kiva.application.usecase.cart.usecase.command.MergeGuestCartUc
import com.kiva.application.usecase.cart.usecase.command.OpenCartUc
import com.kiva.application.usecase.cart.usecase.command.RemoveCartItemUc
import com.kiva.application.usecase.cart.usecase.command.SetCartReservationUc
import com.kiva.application.usecase.cart.usecase.query.GetCartUc
import com.kiva.application.usecase.checkout.usecase.query.GetCheckoutContextUc
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.cart.CartDomainService
import com.kiva.domain.aggregate.inventory.model.valueobject.AdjustmentReason
import com.kiva.domain.aggregate.inventory.model.valueobject.StockDelta
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.cart.RandomCartIds
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentConfig
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AddressIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.CartRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.OfferQmRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.ShopSettingsRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.StoredCatalog
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.CartTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProductVariantQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The checkout page over the real stack on kiva_test: the prototype's bags seeded as the dev seeder does it, the
 * shop's settings as V12 writes them (Zarinpal on, Saman and Mellat off), and customers with tokens from a real login.
 * Step 12 of the purchase roadmap is done when these pass.
 */
class WebV1CheckoutOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val others = StoredCatalog()
    private val transactions = ExposedTransactionManager(stack.db)
    private val carts = CartRepositoryImpl()
    private val offers: OfferQmRepository = OfferQmRepositoryImpl()
    private val settings = ShopSettingsRepositoryImpl()
    private val books = AddressBookRepositoryImpl()
    private val geo = GeoRepositoryImpl()
    private val cartDomain = CartDomainService(RandomCartIds())
    private val cartCommands = CartCommands(transactions, carts, cartDomain, offers, settings, clock)
    private val openCart = OpenCartUc(cartCommands, cartDomain)

    private val cartUseCases = CartUseCases(
        openCart,
        GetCartUc(transactions, carts, offers, settings, clock),
        AddCartItemUc(cartCommands, cartDomain),
        ChangeCartItemUc(cartCommands, cartDomain),
        RemoveCartItemUc(cartCommands, cartDomain),
        ClearCartUc(cartCommands, cartDomain),
        ChooseShippingMethodUc(cartCommands, cartDomain),
        SetCartReservationUc(cartCommands, cartDomain),
        MergeGuestCartUc(cartCommands, cartDomain),
    )

    private val addressUseCases = AddressBookDomainService(geo, AddressIdGeneratorImpl()).let { service ->
        AddressUseCases(
            ListMyAddressesUc(transactions, books, geo),
            GetMyAddressUc(transactions, books, geo),
            AddAddressUc(transactions, service, books, clock),
            UpdateAddressUc(transactions, service, books, clock),
            DeleteAddressUc(transactions, books, clock),
            SetDefaultAddressUc(transactions, books, clock),
        )
    }

    private val checkoutUseCases = CheckoutUseCases(
        openCart,
        GetCheckoutContextUc(
            transactions, carts, offers, settings, books, geo, UserRepositoryImpl(),
            PaymentGateways(listOf(FakePaymentGateway(PaymentConfig.DEFAULT))), clock,
        ),
    )

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        transaction(stack.db) { CartTbl.deleteWhere { userId.isNull() } }
        stack.deleteTestUsers() // their carts and addresses go with them
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
                    webV1Addresses(addressUseCases)
                    webV1Checkout(checkoutUseCases)
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

    private suspend fun ApplicationTestBuilder.checkout(bearer: String?, addressId: String? = null): HttpResponse =
        client.get("$API_V1/checkout" + (addressId?.let { "?addressId=$it" } ?: "")) {
            bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun ApplicationTestBuilder.getCart(bearer: String): JsonObject =
        client.get("$API_V1/cart") { header(HttpHeaders.Authorization, "Bearer $bearer") }.json()

    /** Adds a line a moment after the one before, as in real life, so the lines' order (oldest first) is certain. */
    private suspend fun ApplicationTestBuilder.add(bearer: String, variantId: Long, quantity: Int = 1) {
        clock.advance(Duration.ofSeconds(1))
        val response = client.post("$API_V1/cart/items") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody("""{"variantId":$variantId,"quantity":$quantity}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    /** A new address of the customer's book, a moment after the one before, as in real life; its id. */
    private suspend fun ApplicationTestBuilder.addAddress(bearer: String, setAsDefault: Boolean = false): Long {
        clock.advance(Duration.ofSeconds(1))
        val response = client.post("$API_V1/me/addresses") {
            header(HttpHeaders.Authorization, "Bearer $bearer")
            contentType(ContentType.Application.Json)
            setBody(
                """{"provinceId":8,"cityId":394,"addressLine":"خیابان ولیعصر، کوچه نسترن","postalCode":"1965843117",""" +
                    """"recipientName":"سارا محمدی","recipientPhone":"09123456789","setAsDefault":$setAsDefault}""",
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().long("id")
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.code(): String = json()["code"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json()["errors"]!!.jsonArray.map { "${it.jsonObject["field"]!!.jsonPrimitive.content} ${it.jsonObject["code"]!!.jsonPrimitive.content}" }

    private fun JsonObject.long(name: String): Long = this[name]!!.jsonPrimitive.long

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.list(name: String): List<JsonObject> = this[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.issueCodes(): List<String?> = list("issues").map { it.text("code") }

    private fun JsonObject.without(vararg names: String): JsonObject = JsonObject(filterKeys { it !in names })

    // ============ step 12 is done when ============

    @Test
    fun `without a login it is 401, and a cart without a single line is CART_EMPTY`() = app {
        val anonymous = checkout(bearer = null)
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)

        val empty = checkout(login(phone(801)))
        assertEquals(HttpStatusCode.UnprocessableEntity, empty.status)
        assertEquals("CART_EMPTY", empty.code())
        assertEquals("سبد خریدت خالیه", empty.json().text("message"))
    }

    @Test
    fun `a line that can't be bought is still a line - the page shows it and tells why, once`() = app {
        val bearer = login(phone(802))
        add(bearer, aidaBlack)
        others.stock(VariantId.constructFromPersisted(aidaBlack)) {
            it.adjust(StockDelta.constructFromPersisted(-3), AdjustmentReason.DAMAGED, null, others.staff, clock.now)
        }

        val first = checkout(bearer)

        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val cart = first.json()["cart"]!!.jsonObject
        assertEquals(listOf(false), cart.list("items").map { it["purchasable"]!!.jsonPrimitive.boolean })
        assertEquals(listOf("OUT_OF_STOCK"), cart.issueCodes())
        assertEquals(0, cart["totals"]!!.jsonObject["payable"]!!.jsonPrimitive.int)
        assertEquals(emptyList(), checkout(bearer).json()["cart"]!!.jsonObject.issueCodes())
    }

    @Test
    fun `addresses start with the default, and checkout starts on the default or on the customer's own address asked for`() = app {
        val bearer = login(phone(803))
        add(bearer, nilaOlive)
        val first = addAddress(bearer)
        val second = addAddress(bearer)
        val third = addAddress(bearer, setAsDefault = true)

        val byDefault = checkout(bearer).json()
        assertEquals(listOf(third, first, second), byDefault.list("addresses").map { it.long("id") })
        assertEquals(third, byDefault.long("selectedAddressId"))
        assertEquals(listOf(true, false, false), byDefault.list("addresses").map { it["isDefault"]!!.jsonPrimitive.boolean })
        assertEquals(
            "تهران، تهران، خیابان ولیعصر، کوچه نسترن",
            byDefault.list("addresses").first().text("fullText"),
        )

        val asked = checkout(bearer, addressId = first.toString())
        assertEquals(HttpStatusCode.OK, asked.status)
        assertEquals(first, asked.json().long("selectedAddressId"))
    }

    @Test
    fun `someone else's address, one that is gone and one that is no number are all a 400 on addressId`() = app {
        val mina = login(phone(804))
        val minasAddress = addAddress(mina)
        val sara = login(phone(805))
        add(sara, nilaOlive)
        addAddress(sara)

        for (asked in listOf(minasAddress.toString(), "987654321", "abc", "0", "")) {
            val response = checkout(sara, addressId = asked)
            assertEquals(HttpStatusCode.BadRequest, response.status, "addressId=$asked")
            assertEquals(listOf("addressId ADDRESS_NOT_FOUND"), response.fieldErrors(), "addressId=$asked")
        }
    }

    @Test
    fun `an empty cart is refused before the address is looked at, as placing the order does`() = app {
        val mina = login(phone(806))
        val minasAddress = addAddress(mina)
        val sara = login(phone(807))

        assertEquals("CART_EMPTY", checkout(sara, addressId = minasAddress.toString()).code())
    }

    @Test
    fun `an empty book starts on no address, and the photo goes to the account's mobile until a messenger is kept`() = app {
        val phone = phone(808)
        val bearer = login(phone)
        add(bearer, nilaOlive)

        val fresh = checkout(bearer).json()
        assertEquals(emptyList(), fresh.list("addresses"))
        assertEquals(JsonNull, fresh["selectedAddressId"])
        assertEquals(JsonNull, fresh["selectedMessenger"])
        assertEquals(phone, fresh.text("messengerPhone"))

        transaction(stack.db) {
            UserTbl.update({ UserTbl.phone eq phone }) {
                it[defaultMessenger] = MessengerChannel.TELEGRAM
                it[defaultMessengerPhone] = "09121112233"
            }
        }
        val kept = checkout(bearer).json()
        assertEquals("TELEGRAM", kept.text("selectedMessenger"))
        assertEquals("09121112233", kept.text("messengerPhone"))
    }

    @Test
    fun `the messengers, the gateways, the gift wrap and the terms page`() = app {
        val bearer = login(phone(809))
        add(bearer, nilaOlive)

        val body = checkout(bearer).json()

        val messengers = body.list("messengers")
        assertEquals(listOf("RUBIKA", "TELEGRAM", "BALE"), messengers.map { it.text("channel") })
        assertEquals(listOf("روبیکا", "تلگرام", "بله"), messengers.map { it.text("name") })
        assertEquals(listOf("با شماره موبایل", "با آیدی یا شماره", "با شماره موبایل"), messengers.map { it.text("hint") })
        assertEquals(listOf(true, true, true), messengers.map { it["enabled"]!!.jsonPrimitive.boolean })
        assertEquals(listOf(JsonNull, JsonNull, JsonNull), messengers.map { it["iconUrl"] })

        val gateways = body.list("paymentGateways")
        assertEquals(listOf("ZARINPAL", "SAMAN", "MELLAT"), gateways.map { it.text("code") })
        assertEquals(listOf(true, false, false), gateways.map { it["available"]!!.jsonPrimitive.boolean })
        assertEquals(listOf(true, false, false), gateways.map { it["isDefault"]!!.jsonPrimitive.boolean })
        val zarinpal = gateways.first()
        assertEquals(listOf("زرین‌پال", "#F2C230", "ز"), listOf(zarinpal.text("name"), zarinpal.text("brandColor"), zarinpal.text("initial")))
        assertEquals(JsonNull, zarinpal["logoUrl"])

        assertEquals(Json.parseToJsonElement("""{"available":false,"price":0}"""), body["giftWrap"])
        assertEquals("/pages/terms", body.text("termsUrl"))
        assertEquals(JsonNull, body["consolidation"])
    }

    @Test
    fun `the cart is the one GET cart gives, brought up to date with its issues told once, and the page's options are its own`() = app {
        val bearer = login(phone(810))
        add(bearer, aidaBlack, quantity = 3)
        add(bearer, nilaOlive)
        others.stock(VariantId.constructFromPersisted(aidaBlack)) {
            it.adjust(StockDelta.constructFromPersisted(-1), AdjustmentReason.DAMAGED, null, others.staff, clock.now)
        }

        val body = checkout(bearer).json()
        val cart = body["cart"]!!.jsonObject

        assertEquals(listOf("QUANTITY_REDUCED"), cart.issueCodes())
        assertEquals(listOf(2, 1), cart.list("items").map { it["quantity"]!!.jsonPrimitive.int })
        val sameCart = getCart(bearer)
        assertEquals(emptyList(), sameCart.issueCodes(), "told once: by the checkout")
        assertEquals(sameCart.without("issues"), cart.without("issues"))
        assertEquals(cart["shippingOptions"], body["shippingOptions"])
        assertEquals(cart["reservation"], body["reservation"])
        assertEquals(cart["consolidation"], body["consolidation"])
        assertNull(cart.text("discount"))
    }
}
