package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.contact.usecase.command.CreateContactMessageUc
import com.kiva.application.usecase.contact.usecase.query.ListContactTopicsUc
import com.kiva.application.usecase.newsletter.usecase.command.SubscribeNewsletterUc
import com.kiva.application.usecase.newsletter.usecase.command.UnsubscribeNewsletterUc
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.NewsletterSubscribeHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.NewsletterUnsubscribeHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MessageHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.NewsletterTexts
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

/** The limit per address of `POST /newsletter/subscriptions` (registered in configureHttp; roadmap phase 13, decision 14). */
val NEWSLETTER_RATE_LIMIT = RateLimitName("newsletter")

/** The use cases of the contact form and the newsletter, as the routes need them. */
class ContactUseCases(
    val listTopics: ListContactTopicsUc,
    val create: CreateContactMessageUc,
    val subscribe: SubscribeNewsletterUc,
    val unsubscribe: UnsubscribeNewsletterUc,
)

/**
 * The «تماس با ما» form and the newsletter (contract tag `Content`), mounted under `/api/v1`: the form's topics (public,
 * cached for 5 minutes) and sending a message (a login optional: the message is then the customer's; a broken token is
 * 401, never a guest); subscribing (always the same 200, whether new, back or already there) and the unsubscribe link
 * (always 204 once the token has its shape). Public.
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

    rateLimit(NEWSLETTER_RATE_LIMIT) {
        post("/newsletter/subscriptions") {
            val request = call.receive<NewsletterSubscribeHttpRequest>()
            call.respondResult(useCases.subscribe.execute(SubscribeNewsletterUc.Cmd(request.email, request.source))) {
                MessageHttpResponse(NewsletterTexts.SUBSCRIBED)
            }
        }
    }

    post("/newsletter/subscriptions/unsubscribe") {
        val request = call.receive<NewsletterUnsubscribeHttpRequest>()
        call.respondResult(useCases.unsubscribe.execute(UnsubscribeNewsletterUc.Cmd(request.token)))
    }
}
