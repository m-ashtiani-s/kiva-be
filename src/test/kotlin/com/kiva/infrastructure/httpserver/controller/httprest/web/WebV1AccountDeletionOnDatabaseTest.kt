package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.RequestHash
import com.kiva.application.usecase.account.usecase.command.DeleteMyAccountUc
import com.kiva.application.usecase.account.usecase.command.RequestAccountDeletionUc
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.cart.CartCaller
import com.kiva.application.usecase.cart.usecase.command.AddCartItemUc
import com.kiva.application.usecase.checkout.usecase.command.PlaceOrderUc
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.domain.aggregate.otp.model.valueobject.OtpPurpose
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.persistence.exposed.tbl.OrderQmTbl
import com.kiva.infrastructure.persistence.exposed.tbl.OtpChallengeTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import com.kiva.infrastructure.seed.SeededCatalog
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Deleting an account in two steps over kiva_test (roadmap phase 14, step 14.2): `POST /me/deletion/request` and
 * `DELETE /me`. What goes and what stays with it is AccountErasureOnDatabaseTest's.
 */
class WebV1AccountDeletionOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T08:00:00Z"))
    private val panel = PanelTestStack(clock)
    private val seeded = SeededCatalog(clock)
    private val db = TestDatabase.database
    private val auth get() = panel.purchase.auth

    @BeforeEach
    fun seed() {
        runBlocking { seeded.seeder.seed() }
    }

    @AfterEach
    fun deleteWhatWasStored() {
        panel.deleteWhatWasStored()
        seeded.deleteSeeded()
    }

    private val nila by lazy { listOf("black", "olive", "red", "cream", "gray", "pink", "blue").map { panel.purchase.variantOf("nila-crossbody-bag", it) } }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(name: String): String? = this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private suspend fun ApplicationTestBuilder.requestCode(who: PanelTestStack.Caller): HttpResponse =
        client.post("$API_V1/me/deletion/request") { header(HttpHeaders.Authorization, who.bearer) }

    private suspend fun ApplicationTestBuilder.deleteMe(who: PanelTestStack.Caller, body: String): HttpResponse =
        client.delete("$API_V1/me") {
            header(HttpHeaders.Authorization, who.bearer)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun smsTo(who: PanelTestStack.Caller) = auth.sms.sent.count { it.first == who.user.activePhone }

    private fun isDeleted(who: PanelTestStack.Caller): Boolean =
        transaction(db) { UserTbl.select(UserTbl.deletedOn).where { UserTbl.id eq who.userId }.single()[UserTbl.deletedOn] != null }

    @Test
    fun `a customer with a SHIPPED order gets 422 ACCOUNT_HAS_ACTIVE_ORDERS with its code - and no SMS goes`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(601))
        val code = panel.paidOrders.paid(sara.user.id, nila[0])
        with(ShippedOrders(panel)) { shipped(code, sara, "PT-6010001") }
        val sent = smsTo(sara)

        val refused = requestCode(sara)

        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status, refused.bodyAsText())
        val problem = refused.json()
        assertEquals("ACCOUNT_HAS_ACTIVE_ORDERS", problem.text("code"))
        assertEquals(listOf(code.value), problem.getValue("meta").jsonObject.getValue("orderCodes").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(sent, smsTo(sara), "no code was sent")
    }

    @Test
    fun `an order placed and not paid is active too - after it expires the account may go`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(602))
        val (code, _) = panel.paidOrders.place(sara.user.id, nila[1])

        assertEquals("ACCOUNT_HAS_ACTIVE_ORDERS", requestCode(sara).json().text("code"))

        transaction(db) { assertEquals("PENDING_PAYMENT", OrderQmTbl.select(OrderQmTbl.status).where { OrderQmTbl.code eq code.value }.single()[OrderQmTbl.status].name) }
    }

    @Test
    fun `an OPERATOR gets 422 STAFF_ACCOUNT_CANNOT_BE_DELETED on both steps - and no SMS goes`() = panel.app {
        val staff = panel.caller(PanelTestStack.phone(603), Role.OPERATOR)

        val request = requestCode(staff)
        val delete = deleteMe(staff, """{"otpCode":"12345"}""")

        assertEquals(listOf("STAFF_ACCOUNT_CANNOT_BE_DELETED", "STAFF_ACCOUNT_CANNOT_BE_DELETED"), listOf(request.json().text("code"), delete.json().text("code")))
        assertEquals(HttpStatusCode.UnprocessableEntity, delete.status)
        assertEquals(0, smsTo(staff))
        assertTrue(!isDeleted(staff))
    }

    @Test
    fun `without an active order - a code, then 204 with the cookie cleared, and the token of that moment is 401 on GET me`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(604))

        val sent = requestCode(sara)
        assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
        assertEquals(listOf(sara.user.activePhone.value, "5"), listOf(sent.json().text("phone"), sent.json().text("codeLength")))
        val code = auth.lastCodeSentTo(sara.user.activePhone.value)
        val deleted = deleteMe(sara, """{"otpCode":"${code.value}"}""")

        assertEquals(HttpStatusCode.NoContent, deleted.status, deleted.bodyAsText())
        assertTrue(deleted.headers.getAll(HttpHeaders.SetCookie).orEmpty().contains("kiva_rt=; Max-Age=0; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict"))
        assertTrue(isDeleted(sara))
        val me = client.get("$API_V1/me") { header(HttpHeaders.Authorization, sara.bearer) }
        assertEquals(HttpStatusCode.Unauthorized, me.status)
        assertEquals(HttpStatusCode.Unauthorized, requestCode(sara).status)
    }

    @Test
    fun `a login code is no code for the deletion - 422 OTP_INVALID with the tries left - and the deletion code is no login code`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(605))
        val phone = sara.user.activePhone.value
        requestCode(sara)
        val deletionCode = auth.lastCodeSentTo(phone)
        clock.advance(Duration.ofMinutes(3))
        val fresh = panel.caller(phone)
        val loginSent = runBlocking { auth.send.execute(SendLoginCodeUc.Cmd(phone, null, null)) }
        assertIs<Result.Success<*>>(loginSent)
        val loginCode = auth.lastCodeSentTo(phone)

        val withLoginCode = deleteMe(fresh, """{"otpCode":"${loginCode.value}"}""")
        val loginWithDeletionCode = runBlocking { auth.verify.execute(VerifyLoginCodeUc.Cmd(phone, deletionCode.value, null, null, null)) }

        assertEquals(HttpStatusCode.UnprocessableEntity, withLoginCode.status, withLoginCode.bodyAsText())
        assertEquals(listOf("OTP_INVALID", "4"), listOf(withLoginCode.json().text("code"), withLoginCode.json().getValue("meta").jsonObject.text("attemptsLeft")))
        assertEquals("OTP_INVALID", (loginWithDeletionCode as Result.Failure).failure.code)
        assertTrue(!isDeleted(sara))
    }

    @Test
    fun `a wrong code takes a try that stays - the second wrong one has 3 left, and the account is still there`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(608))
        requestCode(sara)
        val code = auth.lastCodeSentTo(sara.user.activePhone.value)
        val wrong = ((code.value.toInt() + 1) % 100_000).toString().padStart(5, '0')

        val first = deleteMe(sara, """{"otpCode":"$wrong"}""")
        val second = deleteMe(sara, """{"otpCode":"$wrong"}""")

        assertEquals(listOf("4", "3"), listOf(first, second).map { it.json().getValue("meta").jsonObject.text("attemptsLeft") })
        assertTrue(!isDeleted(sara))
        assertEquals(HttpStatusCode.NoContent, deleteMe(sara, """{"otpCode":"${code.value}"}""").status)
    }

    @Test
    fun `no code asked for is 422 OTP_EXPIRED - a code of the wrong shape or a reason too long is 400 on its field, and takes no try`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(606))

        val noCode = deleteMe(sara, """{"otpCode":"12345"}""")
        val badShape = deleteMe(sara, """{"otpCode":"12a","reason":"${"خ".repeat(301)}"}""")

        assertEquals("OTP_EXPIRED", noCode.json().text("code"))
        assertEquals(HttpStatusCode.BadRequest, badShape.status)
        assertEquals(
            listOf("otpCode OTP_CODE_FORMAT_INVALID", "reason DELETION_REASON_TOO_LONG"),
            badShape.json().getValue("errors").jsonArray.map { "${it.jsonObject.text("field")} ${it.jsonObject.text("code")}" },
        )
    }

    @Test
    fun `a code refused for an active order is not used up - not consumed, no try taken`() = panel.app {
        val sara = panel.caller(PanelTestStack.phone(607))
        requestCode(sara)
        val code = auth.lastCodeSentTo(sara.user.activePhone.value)
        panel.paidOrders.place(sara.user.id, nila[2])

        val refused = deleteMe(sara, """{"otpCode":"${code.value}"}""")

        assertEquals("ACCOUNT_HAS_ACTIVE_ORDERS", refused.json().text("code"))
        val challenge = transaction(db) {
            OtpChallengeTbl.selectAll().where { (OtpChallengeTbl.userId eq sara.userId) and (OtpChallengeTbl.purpose eq OtpPurpose.DELETE_ACCOUNT) }.single()
        }
        assertEquals(listOf(null, 0.toShort()), listOf(challenge[OtpChallengeTbl.consumedOn], challenge[OtpChallengeTbl.failedAttempts]))
    }

    /**
     * Placing an order and deleting the account at the same moment, 20 times: either the order is placed and the deletion
     * refused (422), or the account goes and the order is refused (401); never an active order of a deleted account.
     */
    @Test
    fun `placing an order and deleting the account at once - one of them wins, never an active order without its owner`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(20) { round ->
                val customer = panel.caller(PanelTestStack.phone(700 + round))
                runBlocking { panel.purchase.addCartItem.execute(AddCartItemUc.Cmd(CartCaller.Customer(customer.user.id), nila[round % nila.size], 1)) }
                    .getOrNull() ?: fail("not added")
                runBlocking { auth.requestAccountDeletion.execute(RequestAccountDeletionUc.Cmd(customer.user.id, null)) }.getOrNull() ?: fail("no code")
                val code = auth.lastCodeSentTo(customer.user.activePhone.value)
                val start = CountDownLatch(1)

                val placing = pool.submit<Result<*, *>> { start.await(); runBlocking { panel.purchase.placeOrder.execute(placeCommand(customer)) } }
                val deleting = pool.submit<Result<*, *>> {
                    start.await(); runBlocking { auth.deleteMyAccount.execute(DeleteMyAccountUc.Cmd(customer.user.id, code.value, null, null)) }
                }
                start.countDown()
                val placed = placing.get(30, TimeUnit.SECONDS)
                val deleted = deleting.get(30, TimeUnit.SECONDS)

                val orders = transaction(db) { OrderQmTbl.selectAll().where { OrderQmTbl.userId eq customer.userId }.count() }
                if (deleted is Result.Success) {
                    assertIs<Result.Failure<*>>(placed, "round $round: the account went, yet the order was placed")
                    assertEquals("UNAUTHORIZED", (placed.failure as ResultFailure).code, "round $round")
                    assertEquals(0, orders, "round $round")
                } else {
                    assertIs<Result.Success<*>>(placed, "round $round: neither won")
                    assertEquals("ACCOUNT_HAS_ACTIVE_ORDERS", ((deleted as Result.Failure<*>).failure as ResultFailure).code, "round $round")
                    assertEquals(1, orders, "round $round")
                    assertTrue(!isDeleted(customer), "round $round")
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun placeCommand(customer: PanelTestStack.Caller): PlaceOrderUc.Cmd {
        val key = UUID.randomUUID().toString()
        return PlaceOrderUc.Cmd(
            userId = customer.user.id, idempotencyKey = key, requestHash = RequestHash.of(key), addressId = null,
            newAddress = AddressFields(null, 8, 394, "خیابان ولیعصر، کوچه نسترن", "12", "4", "1965843117", "سارا محمدی", "09123456789", false, null, null),
            newAddressAsDefault = null, saveNewAddress = false,
            messenger = PlaceOrderUc.MessengerFields("BALE", "09121112233", null, null, saveAsDefault = false),
            shippingMethod = "POST", reserve = null, paymentGateway = "ZARINPAL", customerNote = null, gift = null, expectedPayable = null,
            acceptTerms = true,
        )
    }
}
