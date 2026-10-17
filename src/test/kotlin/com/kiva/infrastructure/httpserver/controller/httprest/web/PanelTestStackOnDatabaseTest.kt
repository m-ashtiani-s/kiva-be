package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** The panel's test stack itself: its staff members are logged in with the roles they were given, and nothing else. */
class PanelTestStackOnDatabaseTest {

    private val stack = PanelTestStack(MutableClock(Instant.parse("2036-03-01T09:00:00Z")))

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteWhatWasStored()

    @Test
    fun `an admin of the stack gets into a panel page, and a customer of the stack doesn't`() = stack.app {
        val asAdmin = client.get("$API_V1/admin/customers") { header(HttpHeaders.Authorization, stack.callerWith(Role.ADMIN).bearer) }
        val asCustomer = client.get("$API_V1/admin/customers") { header(HttpHeaders.Authorization, stack.callerWith(Role.CUSTOMER).bearer) }

        assertEquals(HttpStatusCode.OK, asAdmin.status)
        assertEquals(HttpStatusCode.Forbidden, asCustomer.status)
    }

    @Test
    fun `support may read the customers but not change roles, which only an admin may`() = stack.app {
        val support = stack.callerWith(Role.SUPPORT)
        val customer = stack.callerWith(Role.CUSTOMER)

        val roles = client.put("$API_V1/admin/customers/${customer.userId}/roles") {
            header(HttpHeaders.Authorization, support.bearer)
            contentType(ContentType.Application.Json)
            setBody("""{"roles": ["SUPPORT"]}""")
        }

        assertEquals(HttpStatusCode.Forbidden, roles.status)
        assertEquals(HttpStatusCode.OK, client.get("$API_V1/admin/customers") { header(HttpHeaders.Authorization, support.bearer) }.status)
    }

    @Test
    fun `every role has its own user`() {
        val ids = Role.entries.map { stack.callerWith(it).userId }.toSet()

        assertEquals(Role.entries.size, ids.size)
        assertNotEquals(stack.callerWith(Role.ADMIN).token, stack.callerWith(Role.OPERATOR).token)
    }
}
