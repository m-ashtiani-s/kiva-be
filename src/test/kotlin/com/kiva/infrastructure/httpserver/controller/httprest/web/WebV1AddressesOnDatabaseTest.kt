package com.kiva.infrastructure.httpserver.controller.httprest.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kiva.application.usecase.address.AddressFields
import com.kiva.application.usecase.address.usecase.command.AddAddressUc
import com.kiva.application.usecase.address.usecase.command.DeleteAddressUc
import com.kiva.application.usecase.address.usecase.command.SetDefaultAddressUc
import com.kiva.application.usecase.address.usecase.command.UpdateAddressUc
import com.kiva.application.usecase.address.usecase.query.GetMyAddressUc
import com.kiva.application.usecase.address.usecase.query.ListMyAddressesUc
import com.kiva.common.railway.Result
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.address.AddressBookDomainService
import com.kiva.domain.aggregate.address.repository.AddressBookRepository
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.ORIGIN
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.AddressBookRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.AddressIdGeneratorImpl
import com.kiva.infrastructure.persistence.exposed.GeoRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.AddressTbl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The address book over the real stack on kiva_test, with tokens from a real login. Step 3 of the purchase roadmap is
 * done when these checks pass.
 */
class WebV1AddressesOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-07-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val transactions = ExposedTransactionManager(stack.db)
    private val books = AddressBookRepositoryImpl()
    private val geo = GeoRepositoryImpl()
    private val service = AddressBookDomainService(geo, AddressIdGeneratorImpl())
    private val useCases = AddressUseCases(
        ListMyAddressesUc(transactions, books, geo),
        GetMyAddressUc(transactions, books, geo),
        AddAddressUc(transactions, service, books, clock),
        UpdateAddressUc(transactions, service, books, clock),
        DeleteAddressUc(transactions, books, clock),
        SetDefaultAddressUc(transactions, books, clock),
    )

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers() // their addresses go with them

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            configureHttp(listOf(ORIGIN))
            configureAuthentication(stack.accessTokens)
            routing {
                route(API_V1) {
                    webV1Auth(AuthUseCases(stack.send, stack.verify, stack.getMe, stack.refresh, stack.logout), listOf(ORIGIN))
                    webV1Addresses(useCases)
                }
            }
        }
        block()
    }

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

    private fun body(
        provinceId: Int = 8,
        cityId: Int = 394,
        addressLine: String = "خیابان ولیعصر، کوچه نسترن",
        postalCode: String = "1965843117",
        recipientName: String = "سارا محمدی",
        recipientPhone: String = "09123456789",
        setAsDefault: Boolean = false,
    ) = """{"title":"خانه","provinceId":$provinceId,"cityId":$cityId,"addressLine":"$addressLine","plaque":"12","unit":"4",""" +
        """"postalCode":"$postalCode","recipientName":"$recipientName","recipientPhone":"$recipientPhone","isSelfRecipient":true,""" +
        """"setAsDefault":$setAsDefault}"""

    private suspend fun ApplicationTestBuilder.add(token: String, json: String = body()): HttpResponse {
        clock.advance(Duration.ofSeconds(1)) // each address saved a moment after the one before, as in real life
        return client.post("$API_V1/me/addresses") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json)
        }
    }

    private suspend fun ApplicationTestBuilder.addId(token: String, json: String = body()): Long {
        val response = add(token, json)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json().jsonObject["id"]!!.jsonPrimitive.long
    }

    private suspend fun ApplicationTestBuilder.list(token: String): JsonArray =
        client.get("$API_V1/me/addresses") { header(HttpHeaders.Authorization, "Bearer $token") }.json().jsonArray

    private suspend fun ApplicationTestBuilder.defaults(token: String): List<Long> =
        list(token).filter { it.jsonObject["isDefault"]!!.jsonPrimitive.boolean }.map { it.jsonObject["id"]!!.jsonPrimitive.long }

    private suspend fun HttpResponse.json(): JsonElement = Json.parseToJsonElement(bodyAsText())

    private suspend fun HttpResponse.fieldErrors(): List<String> =
        json().jsonObject["errors"]!!.jsonArray.map { "${it.jsonObject["field"]!!.jsonPrimitive.content} ${it.jsonObject["code"]!!.jsonPrimitive.content}" }

    private suspend fun HttpResponse.code(): String = json().jsonObject["code"]!!.jsonPrimitive.content

    // ============ step 3 is done when ============

    @Test
    fun `the first address is the default, a second one asked to be becomes it, and there is always exactly one`() = app {
        val token = login(phone(1))

        val first = addId(token)
        assertEquals(listOf(first), defaults(token))

        val second = addId(token, body(setAsDefault = true))
        assertEquals(listOf(second), defaults(token))

        val third = addId(token)
        assertEquals(listOf(second), defaults(token))
        assertEquals(listOf(second, first, third), list(token).map { it.jsonObject["id"]!!.jsonPrimitive.long }, "the default first, then oldest first")
    }

    @Test
    fun `deleting the default makes the oldest one left the default, deleting the last leaves an empty book`() = app {
        val token = login(phone(2))
        val first = addId(token)
        val second = addId(token)
        val third = addId(token)

        assertEquals(HttpStatusCode.NoContent, client.delete("$API_V1/me/addresses/$first") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
        assertEquals(listOf(second), defaults(token))

        for (id in listOf(second, third)) client.delete("$API_V1/me/addresses/$id") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(JsonArray(emptyList()), list(token))
    }

    @Test
    fun `the twenty-first address is ADDRESS_LIMIT_REACHED, and 20 adds at once on 19 let exactly one through`() = app {
        val token = login(phone(3))
        repeat(19) { addId(token) }
        val user = userIdOf(phone(3))
        val fields = AddressFields(
            null, 8, 394, "خیابان ولیعصر، کوچه نسترن", null, null, "1965843117", "سارا محمدی", "09123456789", true, null, null,
        )

        // Each call holds the book a moment after reading it, so without the lock all 20 would surely read 19 addresses.
        val slowBooks = object : AddressBookRepository by books {
            override fun lockAndFind(userId: UserId) = books.lockAndFind(userId).also { Thread.sleep(50) }
        }
        val addSlowly = AddAddressUc(transactions, service, slowBooks, clock)

        val results = atOnce(20) { addSlowly.execute(AddAddressUc.Cmd(user, fields, setAsDefault = null)) }

        assertEquals(1, results.count { it.isSuccess }, "$results")
        assertEquals(19, results.count { it == Result.failure(AddAddressUc.Failure.LimitReached) })
        assertEquals(20, list(token).size)
        val twentyFirst = add(token)
        assertEquals(HttpStatusCode.UnprocessableEntity, twentyFirst.status)
        assertEquals("ADDRESS_LIMIT_REACHED", twentyFirst.code())
    }

    @Test
    fun `the city of Tehran in the province of Isfahan is 422, a province that doesn't exist is 400 on provinceId`() = app {
        val token = login(phone(4))

        val wrongPair = add(token, body(provinceId = 4, cityId = 394))
        assertEquals(HttpStatusCode.UnprocessableEntity, wrongPair.status)
        assertEquals("CITY_NOT_IN_PROVINCE", wrongPair.code())

        val noProvince = add(token, body(provinceId = 99))
        assertEquals(HttpStatusCode.BadRequest, noProvince.status)
        assertEquals(listOf("provinceId PROVINCE_NOT_FOUND"), noProvince.fieldErrors())
    }

    @Test
    fun `a postal code typed in Persian with a dash is kept as 10 digits, and wrong fields come back together`() = app {
        val token = login(phone(5))

        val id = addId(token, body(postalCode = "۱۹۶۵۸-۴۳۱۱۷"))
        val stored = transaction(stack.db) { AddressTbl.selectAll().where { AddressTbl.addressId eq id }.single()[AddressTbl.postalCode] }
        assertEquals("1965843117", stored)

        assertEquals(listOf("postalCode POSTAL_CODE_INVALID"), add(token, body(postalCode = "196584311")).fieldErrors())
        assertEquals(listOf("recipientPhone PHONE_INVALID"), add(token, body(recipientPhone = "0912345")).fieldErrors())
        assertEquals(
            listOf("addressLine ADDRESS_LINE_TOO_SHORT", "postalCode POSTAL_CODE_INVALID", "recipientName RECIPIENT_NAME_TOO_SHORT", "recipientPhone PHONE_INVALID"),
            add(token, body(addressLine = "کوتاه", postalCode = "1", recipientName = "سا", recipientPhone = "1")).fieldErrors(),
        )
    }

    @Test
    fun `someone else's address is ADDRESS_NOT_FOUND for all four operations, and nothing changes`() = app {
        val sara = login(phone(6))
        val mina = login(phone(7))
        val sarasAddress = addId(sara)
        val before = list(sara)

        val answers = listOf(
            client.get("$API_V1/me/addresses/$sarasAddress") { header(HttpHeaders.Authorization, "Bearer $mina") },
            client.put("$API_V1/me/addresses/$sarasAddress") {
                header(HttpHeaders.Authorization, "Bearer $mina")
                contentType(ContentType.Application.Json)
                setBody(body(addressLine = "خیابان انقلاب، کوچه بهار، پلاک ۳"))
            },
            client.delete("$API_V1/me/addresses/$sarasAddress") { header(HttpHeaders.Authorization, "Bearer $mina") },
            client.post("$API_V1/me/addresses/$sarasAddress/default") { header(HttpHeaders.Authorization, "Bearer $mina") },
        )

        assertEquals(List(4) { HttpStatusCode.NotFound }, answers.map { it.status })
        assertEquals(List(4) { "ADDRESS_NOT_FOUND" }, answers.map { it.code() })
        assertEquals(before, list(sara))
    }

    @Test
    fun `an address answers with its province and city names, its full text and when it was saved`() = app {
        val token = login(phone(8))

        val address = add(token).json().jsonObject

        assertEquals("تهران", address["provinceName"]!!.jsonPrimitive.content)
        assertEquals("تهران", address["cityName"]!!.jsonPrimitive.content)
        assertEquals("تهران، تهران، خیابان ولیعصر، کوچه نسترن، پلاک ۱۲، واحد ۴", address["fullText"]!!.jsonPrimitive.content)
        assertEquals(clock.instant().toString(), address["createdAt"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, address["location"])
    }

    @Test
    fun `editing keeps the default where it is unless asked, and making another the default answers with the whole book`() = app {
        val token = login(phone(9))
        val first = addId(token)
        val second = addId(token)

        val edited = client.put("$API_V1/me/addresses/$first") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body(addressLine = "خیابان انقلاب، کوچه بهار، پلاک ۳", setAsDefault = false))
        }
        assertEquals(HttpStatusCode.OK, edited.status)
        assertEquals("خیابان انقلاب، کوچه بهار، پلاک ۳", edited.json().jsonObject["addressLine"]!!.jsonPrimitive.content)
        assertEquals(listOf(first), defaults(token))

        val book = client.post("$API_V1/me/addresses/$second/default") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, book.status)
        assertEquals(listOf(second, first), book.json().jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.long })
    }

    @Test
    fun `no address text, postal code, recipient name or phone reaches the log`() {
        val sara = phone(10)
        val street = "خیابان شریعتی، کوچه یاس"
        val recipient = "مینا رضایی"
        val recipientPhone = "09351234567"
        val postalCode = "1617713534"

        val log = logOf {
            app {
                val token = login(sara)
                val id = addId(token, body(addressLine = street, recipientName = recipient, recipientPhone = recipientPhone, postalCode = postalCode))
                client.put("$API_V1/me/addresses/$id") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(body(addressLine = street, recipientName = recipient, recipientPhone = recipientPhone, postalCode = postalCode))
                }
                add(token, body(addressLine = street, postalCode = "123", recipientPhone = recipientPhone))
                list(token)
                client.delete("$API_V1/me/addresses/$id") { header(HttpHeaders.Authorization, "Bearer $token") }
            }
        }

        val text = log.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        for (secret in listOf(street, "یاس", recipient, recipientPhone, postalCode, "16177")) {
            assertTrue(secret !in text, "'$secret' is in the log")
        }
        assertTrue(log.any { "AddAddressUc.execute" in it.formattedMessage }, "the use cases' own lines were caught")
    }

    private fun userIdOf(phone: String): UserId =
        transaction(stack.db) { UserId.constructFromPersisted(UserTbl.selectAll().where { UserTbl.phone eq phone }.single()[UserTbl.id].value) }

    /** Runs [work] on [n] threads released together, so the database really sees the calls at the same moment. */
    private fun <T> atOnce(n: Int, work: suspend () -> T): List<T> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(n)
        return try {
            val calls = List(n) { pool.submit<T> { start.await(); runBlocking { work() } } }
            start.countDown()
            calls.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdown()
        }
    }

    private fun logOf(block: () -> Unit): List<ILoggingEvent> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val sql = LoggerFactory.getLogger("Exposed") as Logger
        val sqlLevel = sql.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        sql.level = Level.DEBUG
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
            sql.level = sqlLevel
        }
        return appender.list.toList()
    }
}
