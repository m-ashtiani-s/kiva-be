package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.product.usecase.query.FindProductsUc
import com.kiva.application.usecase.product.usecase.query.ProductSorts
import com.kiva.common.railway.unwrapOr
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductListHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProductSummaryHttpResponse
import io.ktor.http.Parameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The shop's products (contract tag `Catalog`). Mounted under `/api/v1`.
 *
 * The route only reads the request as it came: paging is checked here (it is the same for every list), the filters
 * by FindProductsUc. The status of each failure comes from its kind (see respondResult).
 */
fun Route.webV1Products(findProductsUc: FindProductsUc) {
    get("/products") {
        // The contract's product grid: 24 a page, at most 60.
        val pageable = call.pageable(allowedSorts = ProductSorts.names, defaultSort = ProductSorts.DEFAULT, defaultSize = 24, maxSize = 60)
            .unwrapOr { return@get call.respondFailure(it) }
        val params = call.request.queryParameters
        val query = FindProductsUc.Qry(
            pageable = pageable,
            q = params["q"],
            categories = params.list("category"),
            colors = params.list("color"),
            minPrice = params["minPrice"],
            maxPrice = params["maxPrice"],
            onSale = params.flag("onSale"),
            inStock = params.flag("inStock"),
            isNew = params.flag("isNew"),
            hasVideo = params.flag("hasVideo"),
            bagTypes = params.list("bagType"),
            materials = params.list("material"),
            occasions = params.list("occasion"),
            ids = params.list("ids"),
        )

        call.respondResult(findProductsUc.execute(query)) { page ->
            val list = page.toHttpResponse { ProductSummaryHttpResponse.from(it, filterColors = query.colors) }
            ProductListHttpResponse(list.items, list.meta, sort = pageable.sort ?: ProductSorts.DEFAULT)
        }
    }
}

/** `category=shoulder,cross` as the contract sends it, or the parameter repeated; empty pieces are dropped. */
private fun Parameters.list(name: String): List<String> =
    getAll(name).orEmpty().flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }

/** Only `true` turns a filter on; a missing or any other value leaves it off. */
private fun Parameters.flag(name: String): Boolean = get(name)?.trim().equals("true", ignoreCase = true)
