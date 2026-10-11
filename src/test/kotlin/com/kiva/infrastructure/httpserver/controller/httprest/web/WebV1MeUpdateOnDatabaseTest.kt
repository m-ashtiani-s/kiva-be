package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.account.usecase.command.UpdateMyProfileUc
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import com.kiva.domain.aggregate.user.repository.UserRepository
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.controller.httprest.web.AuthTestStack.Companion.phone
import com.kiva.infrastructure.persistence.exposed.UserRepositoryImpl
import com.kiva.infrastructure.persistence.exposed.tbl.UserTbl
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * PATCH /me over kiva_test, with a token from a real login. Step 8 of the login plan is done when these pass. Test
 * phones start with 0990 and are deleted after each test.
 */
class WebV1MeUpdateOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2035-06-01T09:00:00Z"))
    private val stack = AuthTestStack(clock)
    private val db = stack.db

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteTestUsers()

    /** Logs [phone] in and gives back its access token. */
    private suspend fun ApplicationTestBuilder.login(phone: String): String {
        client.post("$API_V1/auth/otp/send") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone"}""")
        }
        val response = client.post("$API_V1/auth/otp/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"phone":"$phone","code":"${stack.lastCodeSentTo(phone).value}"}""")
        }
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["accessToken"]?.jsonPrimitive?.content ?: error("no token")
    }

    private suspend fun ApplicationTestBuilder.edit(token: String, body: String): HttpResponse =
        client.patch("$API_V1/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun stored(phone: String) = transaction(db) { UserTbl.selectAll().where { UserTbl.phone eq phone }.single() }

    private val fullProfile = """
        {"firstName": "سارا", "lastName": "محمدی", "email": "Sara@X.com", "birthDate": "1996-09-10", "gender": "FEMALE",
         "defaultMessenger": "TELEGRAM", "defaultMessengerPhone": "09351112233", "marketingSmsOptIn": true}
    """.trimIndent()

    @Test
    fun `a full profile is saved and answered as the contract's User`() = stack.app {
        val token = login(phone(1))

        val response = edit(token, fullProfile)

        assertEquals(HttpStatusCode.OK, response.status)
        val user = response.json()
        assertEquals("سارا", user.text("firstName"))
        assertEquals("سارا محمدی", user.text("fullName"))
        assertEquals("سارا", user.text("displayName"))
        assertEquals("س", user.text("avatarInitial"))
        assertEquals("sara@x.com", user.text("email"))
        assertEquals("1996-09-10", user.text("birthDate"))
        assertEquals("FEMALE", user.text("gender"))
        assertEquals("TELEGRAM", user.text("defaultMessenger"))
        assertEquals("09351112233", user.text("defaultMessengerPhone"))
        assertEquals("true", user.text("marketingSmsOptIn"))
        assertEquals(1L, stored(phone(1))[UserTbl.version])
    }

    @Test
    fun `an empty body changes nothing, not even the version`() = stack.app {
        val token = login(phone(2))
        val before = edit(token, fullProfile).json()

        val after = edit(token, "{}")

        assertEquals(HttpStatusCode.OK, after.status)
        assertEquals(before, after.json())
        assertEquals(1L, stored(phone(2))[UserTbl.version])
    }

    @Test
    fun `a field sent as null is cleared, one sent with a value is set, and the rest stay`() = stack.app {
        val token = login(phone(3))
        edit(token, fullProfile)

        val cleared = edit(token, """{"firstName": null}""").json()
        assertEquals(null, cleared.text("firstName"))
        assertEquals("دوست عزیز", cleared.text("displayName"))
        assertEquals("محمدی", cleared.text("lastName"))
        assertEquals("sara@x.com", cleared.text("email"))

        val set = edit(token, """{"firstName": "مریم"}""").json()
        assertEquals("مریم", set.text("firstName"))
        assertEquals("مریم محمدی", set.text("fullName"))
    }

    @Test
    fun `two wrong fields are one 400 with two errors, and nothing is saved`() = stack.app {
        val token = login(phone(4))

        val response = edit(token, """{"firstName": "<b>", "email": "bad", "lastName": "رضایی"}""")

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.json()
        assertEquals("VALIDATION_ERROR", body.text("code"))
        val errors = body["errors"]?.jsonArray?.map { it.jsonObject.text("field") to it.jsonObject.text("code") }
        assertEquals(listOf("firstName" to "PERSON_NAME_INVALID", "email" to "EMAIL_INVALID"), errors)
        assertEquals(null, stored(phone(4))[UserTbl.lastName])
    }

    @Test
    fun `a messenger number without a messenger, or a birth date that isn't one, is 400 on its field`() = stack.app {
        val token = login(phone(5))

        val messenger = edit(token, """{"defaultMessengerPhone": "09351112233"}""").json()
        assertEquals("defaultMessenger" to "MESSENGER_REQUIRED", messenger["errors"]?.jsonArray?.single()?.jsonObject?.let { it.text("field") to it.text("code") })

        val date = edit(token, """{"birthDate": "10/09/1996"}""").json()
        assertEquals("birthDate" to "BIRTH_DATE_INVALID", date["errors"]?.jsonArray?.single()?.jsonObject?.let { it.text("field") to it.text("code") })

        val future = edit(token, """{"birthDate": "2036-01-01"}""").json()
        assertEquals("BIRTH_DATE_INVALID", future["errors"]?.jsonArray?.single()?.jsonObject?.text("code"))
    }

    @Test
    fun `roles and phone in the body change nothing`() = stack.app {
        val token = login(phone(6))

        val response = edit(token, """{"roles": ["CUSTOMER", "ADMIN"], "phone": "09351112233", "firstName": "سارا"}""")

        assertEquals(HttpStatusCode.OK, response.status)
        val user = response.json()
        assertEquals(listOf("CUSTOMER"), user["roles"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertEquals(phone(6), user.text("phone"))
        assertEquals(listOf("CUSTOMER"), stored(phone(6))[UserTbl.roles])
        assertEquals(phone(6), stored(phone(6))[UserTbl.phone])
    }

    /**
     * Both edits read the profile before either saves (a barrier holds each after its read), as two tabs saving at the
     * same moment would: one is saved, the other is refused with 409, and the profile is exactly the saved one's.
     */
    @Test
    fun `two edits at the same moment, one is saved and the other is 409, none is lost unnoticed`() {
        val bothRead = CyclicBarrier(2)
        val slowUsers = object : UserRepository by UserRepositoryImpl() {
            override fun findById(id: UserId): User? = UserRepositoryImpl().findById(id).also { bothRead.await(30, TimeUnit.SECONDS) }
        }
        val racingEdit = UpdateMyProfileUc(ExposedTransactionManager(db), slowUsers, clock)
        var token = ""
        stack.app { token = login(phone(7)) }

        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                configureHttp()
                configureAuthentication(stack.accessTokens)
                routing { route(API_V1) { webV1Me(MeUseCases(stack.getMe, racingEdit, stack.requestPhoneChange, stack.confirmPhoneChange)) } }
            }
            val answers = coroutineScope {
                listOf("سارا", "مریم").map { name -> async { edit(token, """{"firstName": "$name"}""") } }.awaitAll()
            }

            assertEquals(listOf(HttpStatusCode.OK, HttpStatusCode.Conflict), answers.map { it.status }.sortedBy { it.value })
            val saved = answers.single { it.status == HttpStatusCode.OK }.json().text("firstName")
            assertEquals("CHANGED_BY_SOMEONE_ELSE", answers.single { it.status == HttpStatusCode.Conflict }.json().text("code"))
            assertEquals(saved, stored(phone(7))[UserTbl.firstName])
            assertEquals(1L, stored(phone(7))[UserTbl.version])
        }
    }

    @Test
    fun `a stored name is kept normalized`() = stack.app {
        val token = login(phone(8))

        edit(token, """{"firstName": "  علي "}""")

        assertEquals("علی", stored(phone(8))[UserTbl.firstName])
    }
}
