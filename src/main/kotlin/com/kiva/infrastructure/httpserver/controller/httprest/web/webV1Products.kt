package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.product.ProductFilterQuery
import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.FindRelatedProductsUc
import com.kiva.application.usecase.product.usecase.query.GetProductFacetsUc
import com.kiva.application.usecase.product.usecase.query.GetProductUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductDetailHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductFacetsHttpResponse
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
    get("/products") {
        // The contract's product grid: 24 a page, at most 60.
        val pageable = call.pageable(allowedSorts = ProductSorts.names, defaultSort = ProductSorts.DEFAULT, defaultSize = 24, maxSize = 60)
            .unwrapOr { return@get call.respondFailure(it) }
        val filters = call.request.queryParameters.productFilters()

        val list = findProductsUc.execute(FindProductsUc.Qry(pageable, filters)).unwrapOr { return@get call.respondFailure(it) }
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
        val query = GetProductUc.Qry(idOrSlug = call.pathParameters["idOrSlug"].orEmpty(), color = call.request.queryParameters["color"])
        call.respondResult(getProductUc.execute(query)) { ProductDetailHttpResponse.from(it, siteUrl, mediaUrls) }
    }

    get("/products/{productId}/related") {
        val query = FindRelatedProductsUc.Qry(productId = call.pathParameters["productId"].orEmpty(), limit = call.request.queryParameters["limit"])
        call.respondResult(findRelatedProductsUc.execute(query)) { cards -> cards.map { ProductSummaryHttpResponse.from(it, filterColors = emptyList(), mediaUrls) } }
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
