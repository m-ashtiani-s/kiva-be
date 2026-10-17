package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.application.usecase.product.usecase.command.UpdateProductUc
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
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ProductUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductListItemHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's products, as the routes need them. */
class AdminProductUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findProducts: FindAdminProductsUc,
    val getProduct: GetAdminProductUc,
    val createProduct: CreateProductUc,
    val updateProduct: UpdateProductUc,
)

private const val PRODUCT_ID = "productId"

/**
 * The panel's products (contract tag `Admin · Catalog`, `[ADMIN, CATALOG_MANAGER]`). Mounted under `/api/v1`. Every
 * answer with one product carries its version in the `ETag` header and the body's `etag`.
 *
 * `PUT` reads the `If-Match` header as it came; the use case says whether it is one of the accepted shapes.
 *
 * @param siteUrl the shop's address, for the product page's canonical address.
 * @param mediaUrls makes the gallery's addresses full.
 */
fun Route.webV1AdminProducts(useCases: AdminProductUseCases, siteUrl: String, mediaUrls: MediaUrls) {
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
            call.respondResult(useCases.findProducts.execute(query)) { page -> page.toHttpResponse { AdminProductListItemHttpResponse.from(it, mediaUrls) } }
        }

        get("/admin/products/{$PRODUCT_ID}") {
            call.respondProduct(useCases, call.pathParameters[PRODUCT_ID].orEmpty(), HttpStatusCode.OK, siteUrl, mediaUrls)
        }

        post("/admin/products") {
            val request = call.receive<ProductUpsertHttpRequest>()
            val command = CreateProductUc.Cmd(request.toFields(), request.status, request.toVariants(), call.panelActor())
            val created = useCases.createProduct.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondProduct(useCases, created.productId.toString(), HttpStatusCode.Created, siteUrl, mediaUrls)
        }

        put("/admin/products/{$PRODUCT_ID}") {
            val request = call.receive<ProductUpsertHttpRequest>()
            val command = UpdateProductUc.Cmd(
                productId = call.pathParameters[PRODUCT_ID].orEmpty(),
                fields = request.toFields(),
                ifMatch = call.request.headers[HttpHeaders.IfMatch],
                actor = call.panelActor(),
            )
            val id = useCases.updateProduct.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondProduct(useCases, id.value.toString(), HttpStatusCode.OK, siteUrl, mediaUrls)
        }
    }
}

/** The product as the panel's page shows it, with its version in the `ETag` header. */
private suspend fun ApplicationCall.respondProduct(useCases: AdminProductUseCases, productId: String, status: HttpStatusCode, siteUrl: String, mediaUrls: MediaUrls) {
    respondResultWithETag(useCases.getProduct.execute(GetAdminProductUc.Qry(productId)), status, etagOf = { VersionETag.of(it.version) }) {
        AdminProductHttpResponse.from(it, siteUrl, mediaUrls)
    }
}
