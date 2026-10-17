package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.product.usecase.query.AdminProductSorts
import com.kiva.application.usecase.product.usecase.query.FindAdminProductsUc
import com.kiva.application.usecase.product.usecase.query.GetAdminProductUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.VersionETag
import com.kiva.infrastructure.common.http.pageable
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.common.http.respondResultWithETag
import com.kiva.infrastructure.common.http.toHttpResponse
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductListItemHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** The use cases of the panel's products, as the routes need them. */
class AdminProductUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findProducts: FindAdminProductsUc,
    val getProduct: GetAdminProductUc,
)

private const val PRODUCT_ID = "productId"

/**
 * The panel's products (contract tag `Admin · Catalog`, `[ADMIN, CATALOG_MANAGER]`). Mounted under `/api/v1`. Every
 * answer with one product carries its version in the `ETag` header and the body's `etag`.
 *
 * @param siteUrl the shop's address, for the product page's canonical address.
 */
fun Route.webV1AdminProducts(useCases: AdminProductUseCases, siteUrl: String) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CATALOG_MANAGER) {
        get("/admin/products") {
            val pageable = call.pageable(allowedSorts = AdminProductSorts.names, defaultSort = AdminProductSorts.DEFAULT)
                .unwrapOr { return@get call.respondFailure(it) }
            val params = call.request.queryParameters
            val query = FindAdminProductsUc.Qry(
                pageable = pageable,
                q = params["q"],
                status = params["status"],
                categories = params.getAll("category").orEmpty().flatMap { it.split(',') }.filter { it.isNotBlank() },
                stockStatus = params["stockStatus"],
            )
            call.respondResult(useCases.findProducts.execute(query)) { page -> page.toHttpResponse(AdminProductListItemHttpResponse::from) }
        }

        get("/admin/products/{$PRODUCT_ID}") {
            val query = GetAdminProductUc.Qry(call.pathParameters[PRODUCT_ID].orEmpty())
            call.respondResultWithETag(useCases.getProduct.execute(query), etagOf = { VersionETag.of(it.version) }) {
                AdminProductHttpResponse.from(it, siteUrl)
            }
        }
    }
}
