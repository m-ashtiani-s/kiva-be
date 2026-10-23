package com.kiva.infrastructure.httpserver.controller.httprest.web

import io.ktor.server.auth.principal
import io.ktor.server.auth.authenticate
import com.kiva.infrastructure.httpserver.auth.AccessPrincipal
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductInquiryUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductFacetsHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductInquiryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductSummaryHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.Parameters
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The shop's products (contract tags `Catalog` and `Product`). Mounted under `/api/v1`.
 *
 * The routes only read the request as it came: paging is checked here (it is the same for every list), everything
 * else by the use cases. The status of each failure comes from its kind (see respondResult). Ktor prefers a fixed
 * segment to a parameter, so `/products/facets` never reaches `/products/{idOrSlug}`.
 */
fun Route.webV1Products(
    findProductsUc: FindProductsUc,
    getProductFacetsUc: GetProductFacetsUc,
    getProductUc: GetProductUc,
    findRelatedProductsUc: FindRelatedProductsUc,
    siteUrl: String,
    mediaUrls: MediaUrls,
) {
    // A login is optional (the hearts); a broken token is 401, as on the reviews' list.
    authenticate(ACCESS, optional = true) { webV1ProductRoutes(findProductsUc, getProductFacetsUc, getProductUc, findRelatedProductsUc, siteUrl, mediaUrls) }
}

private fun Route.webV1ProductRoutes(
    findProductsUc: FindProductsUc,
    getProductFacetsUc: GetProductFacetsUc,
    getProductUc: GetProductUc,
    findRelatedProductsUc: FindRelatedProductsUc,
    siteUrl: String,
    mediaUrls: MediaUrls,
) {
    get("/products") {
        // The contract's product grid: 24 a page, at most 60.
        val pageable = call.pageable(allowedSorts = ProductSorts.names, defaultSort = ProductSorts.DEFAULT, defaultSize = 24, maxSize = 60)
            .unwrapOr { return@get call.respondFailure(it) }
        val filters = call.request.queryParameters.productFilters()

        val list = findProductsUc.execute(FindProductsUc.Qry(pageable, filters, call.principal<AccessPrincipal>()?.userId)).unwrapOr { return@get call.respondFailure(it) }
        // Only when asked for: the counts take a few more queries.
        val facets = if (call.request.queryParameters.flag("includeFacets")) {
            getProductFacetsUc.execute(GetProductFacetsUc.Qry(filters)).unwrapOr { return@get call.respondFailure(it) }
        } else {
            null
        }
        call.respond(ProductListHttpResponse.from(list, facets, sort = pageable.sort ?: ProductSorts.DEFAULT, mediaUrls))
    }

    get("/products/facets") {
        val query = GetProductFacetsUc.Qry(call.request.queryParameters.productFilters())
        call.respondResult(getProductFacetsUc.execute(query)) { ProductFacetsHttpResponse.from(it) }
    }

    // `1`, `KV-1001` or `mahak-shoulder-bag`: the use case tells them apart.
    get("/products/{idOrSlug}") {
        // Always present: the route only matches when the segment is there.
        val query = GetProductUc.Qry(
            idOrSlug = call.pathParameters["idOrSlug"].orEmpty(),
            color = call.request.queryParameters["color"],
            viewer = call.principal<AccessPrincipal>()?.userId,
        )
        call.respondResult(getProductUc.execute(query)) { ProductDetailHttpResponse.from(it, siteUrl, mediaUrls) }
    }

    get("/products/{productId}/related") {
        val query = FindRelatedProductsUc.Qry(
            productId = call.pathParameters["productId"].orEmpty(),
            limit = call.request.queryParameters["limit"],
            viewer = call.principal<AccessPrincipal>()?.userId,
        )
        call.respondResult(findRelatedProductsUc.execute(query)) { cards -> cards.map { ProductSummaryHttpResponse.from(it, filterColors = emptyList(), mediaUrls) } }
    }
}

/**
 * «سؤال داری؟» of a product page (contract tag `Product`; roadmap section 15.2, decision 18): public, no login read, and
 * not cached (the links follow the settings at once). Mounted under `/api/v1`.
 */
fun Route.webV1ProductInquiry(getProductInquiryUc: GetProductInquiryUc) {
    get("/products/{productId}/inquiry") {
        val query = GetProductInquiryUc.Qry(productId = call.pathParameters["productId"].orEmpty(), color = call.request.queryParameters["color"])
        call.respondResult(getProductInquiryUc.execute(query)) { ProductInquiryHttpResponse.from(it) }
    }
}

/** The list's filters as the contract names them; the list and its facets read the same ones. */
private fun Parameters.productFilters() = ProductFilterQuery(
    q = this["q"],
    categories = list("category"),
    colors = list("color"),
    minPrice = this["minPrice"],
    maxPrice = this["maxPrice"],
    onSale = flag("onSale"),
    inStock = flag("inStock"),
    isNew = flag("isNew"),
    hasVideo = flag("hasVideo"),
    bagTypes = list("bagType"),
    materials = list("material"),
    occasions = list("occasion"),
    ids = list("ids"),
)

/** `category=shoulder,cross` as the contract sends it, or the parameter repeated; empty pieces are dropped. */
private fun Parameters.list(name: String): List<String> =
    getAll(name).orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }

/** Only `true` turns a filter on; a missing or any other value leaves it off. */
private fun Parameters.flag(name: String): Boolean = get(name)?.trim().equals("true", ignoreCase = true)
