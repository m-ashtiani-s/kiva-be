package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.contact.usecase.command.CreateContactMessageUc
import com.kiva.application.usecase.contact.usecase.query.ListContactTopicsUc
import com.kiva.domain.aggregate.contact.model.ContactMessageDraft
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.AccessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ContactMessageHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ContactMessageHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ContactTopicOptionHttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The limit per address of `POST /contact/messages` (registered in configureHttp; roadmap phase 13, decision 3). */
val CONTACT_RATE_LIMIT = RateLimitName("contact")

/** The use cases of the contact form, as the routes need them. */
class ContactUseCases(
    val listTopics: ListContactTopicsUc,
    val create: CreateContactMessageUc,
)

/**
 * The «تماس با ما» form (contract tag `Content`), mounted under `/api/v1`: its topics (public, cached for 5 minutes) and
 * sending a message (a login optional: the message is then the customer's; a broken token is 401, never a guest).
 */
fun Route.webV1Contact(useCases: ContactUseCases) {
    get("/contact/topics") {
        call.respondResultCacheable(useCases.listTopics.execute(ListContactTopicsUc.Qry)) { topics -> topics.map { ContactTopicOptionHttpResponse.from(it) } }
    }

    rateLimit(CONTACT_RATE_LIMIT) {
        authenticate(ACCESS, optional = true) {
            post("/contact/messages") {
                val request = call.receive<ContactMessageHttpRequest>()
                val input = ContactMessageDraft.Input(request.topic, request.fullName, request.phone, request.email, request.orderCode, request.message)
                val command = CreateContactMessageUc.Cmd(call.principal<AccessPrincipal>()?.userId, input)
                call.respondResult(useCases.create.execute(command), HttpStatusCode.Created) { ContactMessageHttpResponse.from(it) }
            }
        }
    }
}
