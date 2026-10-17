package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.product.usecase.command.AddVariantUc
import com.kiva.application.usecase.product.usecase.command.ArchiveProductUc
import com.kiva.application.usecase.product.usecase.command.ChangeProductStatusUc
import com.kiva.application.usecase.product.usecase.command.CreateProductUc
import com.kiva.application.usecase.product.usecase.command.DeactivateVariantUc
import com.kiva.application.usecase.product.usecase.command.UpdateProductUc
import com.kiva.application.usecase.product.usecase.command.UpdateVariantUc
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
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ProductStatusHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ProductUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.VariantUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminProductListItemHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminVariantHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's products, as the routes need them. */
class AdminProductUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val findProducts: FindAdminProductsUc,
    val getProduct: GetAdminProductUc,
    val createProduct: CreateProductUc,
    val updateProduct: UpdateProductUc,
    val changeStatus: ChangeProductStatusUc,
    val archiveProduct: ArchiveProductUc,
    val addVariant: AddVariantUc,
    val updateVariant: UpdateVariantUc,
    val deactivateVariant: DeactivateVariantUc,
)

private const val PRODUCT_ID = "productId"
private const val VARIANT_ID = "variantId"

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

        patch("/admin/products/{$PRODUCT_ID}/status") {
            val request = call.receive<ProductStatusHttpRequest>()
            val command = ChangeProductStatusUc.Cmd(call.pathParameters[PRODUCT_ID].orEmpty(), request.status, request.publishAt, call.panelActor())
            val id = useCases.changeStatus.execute(command).unwrapOr { return@patch call.respondFailure(it) }
            call.respondProduct(useCases, id.value.toString(), HttpStatusCode.OK, siteUrl, mediaUrls)
        }

        delete("/admin/products/{$PRODUCT_ID}") {
            call.respondResult(useCases.archiveProduct.execute(ArchiveProductUc.Cmd(call.pathParameters[PRODUCT_ID].orEmpty(), call.panelActor())))
        }

        // A color's answers are that color as the panel's page shows it, with the product's version in the ETag.
        post("/admin/products/{$PRODUCT_ID}/variants") {
            val request = call.receive<VariantUpsertHttpRequest>()
            val command = AddVariantUc.Cmd(call.pathParameters[PRODUCT_ID].orEmpty(), request.toVariant(), call.panelActor())
            val added = useCases.addVariant.execute(command).unwrapOr { return@post call.respondFailure(it) }
            call.respondVariant(useCases, added.productId.value.toString(), added.variantId.value, HttpStatusCode.Created, mediaUrls)
        }

        put("/admin/products/{$PRODUCT_ID}/variants/{$VARIANT_ID}") {
            val request = call.receive<VariantUpsertHttpRequest>()
            val productId = call.pathParameters[PRODUCT_ID].orEmpty()
            val command = UpdateVariantUc.Cmd(productId, call.pathParameters[VARIANT_ID].orEmpty(), request.toVariant(), call.panelActor())
            val variantId = useCases.updateVariant.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondVariant(useCases, productId, variantId.value, HttpStatusCode.OK, mediaUrls)
        }

        delete("/admin/products/{$PRODUCT_ID}/variants/{$VARIANT_ID}") {
            val command = DeactivateVariantUc.Cmd(call.pathParameters[PRODUCT_ID].orEmpty(), call.pathParameters[VARIANT_ID].orEmpty(), call.panelActor())
            call.respondResult(useCases.deactivateVariant.execute(command))
        }
    }
}

/** The product as the panel's page shows it, with its version in the `ETag` header. */
private suspend fun ApplicationCall.respondProduct(useCases: AdminProductUseCases, productId: String, status: HttpStatusCode, siteUrl: String, mediaUrls: MediaUrls) {
    respondResultWithETag(useCases.getProduct.execute(GetAdminProductUc.Qry(productId)), status, etagOf = { VersionETag.of(it.version) }) {
        AdminProductHttpResponse.from(it, siteUrl, mediaUrls)
    }
}

/** One color of the product as the panel's page shows it (AdminVariant), with the product's version in the `ETag` header. */
private suspend fun ApplicationCall.respondVariant(useCases: AdminProductUseCases, productId: String, variantId: Long, status: HttpStatusCode, mediaUrls: MediaUrls) {
    respondResultWithETag(useCases.getProduct.execute(GetAdminProductUc.Qry(productId)), status, etagOf = { VersionETag.of(it.version) }) { product ->
        AdminVariantHttpResponse.from(product.variants.single { it.shop.id == variantId }, mediaUrls)
    }
}
