package com.kiva.infrastructure.httpserver

import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.infrastructure.common.http.KivaJson
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.common.http.configureStatusPages
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Catalog
import com.kiva.infrastructure.httpserver.controller.httprest.web.webV1Products
import com.kiva.infrastructure.httpserver.route.healthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject
import org.slf4j.event.Level
import java.util.UUID

/** Every public API route lives under this prefix (from the API contract). `/health` stays outside it. */
const val API_V1 = "/api/v1"

/** Response header carrying the id of the request, the same id as `traceId` in error bodies and in the log. */
const val TRACE_ID_HEADER = "X-Trace-Id"

/** Request header of POST /orders and the payment retries (contract `Idempotency-Key`). */
const val IDEMPOTENCY_KEY_HEADER = "Idempotency-Key"

/** A guest's cart, sent by the client and handed out by the server (contract `X-Cart-Token`). */
const val CART_TOKEN_HEADER = "X-Cart-Token"

/**
 * @param allowedOrigins the frontends a browser may call the API from (`kiva.http.allowedOrigins`). Empty in tests that
 *   don't care: then no browser origin is allowed, which is the safe side.
 */
fun Application.configureHttp(allowedOrigins: List<String> = emptyList()) {
    install(ContentNegotiation) {
        json(KivaJson)
    }
    // The frontend lives on another origin (localhost:5173 in development, kiva.ir in production), so the browser
    // asks before every call that isn't "simple" (JSON body, Authorization, Idempotency-Key, ...) and blocks the
    // answer unless these headers allow it. Exact origins only, never "*": the API takes logins and payments.
    install(CORS) {
        val origins = allowedOrigins.toSet()
        allowOrigins { it in origins }
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true // application/json bodies
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.IfMatch)
        allowHeader(IDEMPOTENCY_KEY_HEADER)
        allowHeader(CART_TOKEN_HEADER)
        // Response headers the frontend's JavaScript may read; the browser hides all others.
        exposeHeader(TRACE_ID_HEADER)
        exposeHeader(CART_TOKEN_HEADER)
        exposeHeader(HttpHeaders.ETag)
        exposeHeader(HttpHeaders.RetryAfter)
        // The browser may reuse an allowed preflight for this long instead of asking before every call.
        maxAgeInSeconds = 600
    }
    // A short random id per request: shown to the client on errors and printed on every log line of that
    // request, so "the error with traceId 4bf92f3577b34da6" can be found in the log.
    install(CallId) {
        generate { UUID.randomUUID().toString().replace("-", "").take(16) }
        replyToHeader(TRACE_ID_HEADER)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("traceId")
    }
    configureStatusPages()
}

fun Application.configureRouting() {
    val listColorsUc by inject<ListColorsUc>()
    val listMaterialsUc by inject<ListMaterialsUc>()
    val listCategoriesUc by inject<ListCategoriesUc>()
    val getCategoryUc by inject<GetCategoryUc>()
    val findProductsUc by inject<FindProductsUc>()
    val getProductFacetsUc by inject<GetProductFacetsUc>()
    val getProductUc by inject<GetProductUc>()
    val findRelatedProductsUc by inject<FindRelatedProductsUc>()
    val config by inject<AppConfig>()

    routing {
        healthRoutes()
        route(API_V1) {
            webV1Catalog(listColorsUc, listMaterialsUc, listCategoriesUc, getCategoryUc)
            webV1Products(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, config.shop.siteUrl)
        }
    }
}
