package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.catalog.usecase.command.CreateCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.CreateColorUc
import com.kiva.application.usecase.catalog.usecase.command.CreateMaterialUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteColorUc
import com.kiva.application.usecase.catalog.usecase.command.DeleteMaterialUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateCategoryUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateColorUc
import com.kiva.application.usecase.catalog.usecase.command.UpdateMaterialUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminColorUc
import com.kiva.application.usecase.catalog.usecase.query.GetAdminMaterialUc
import com.kiva.application.usecase.catalog.usecase.query.ListAdminCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListAdminMaterialsUc
import com.kiva.common.railway.unwrapOr
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.panelActor
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.CategoryUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.ColorUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.MaterialUpsertHttpRequest
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.AdminCategoryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ColorHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MaterialHttpResponse
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** The use cases of the panel's categories, colors and materials, as the routes need them. */
class AdminCatalogUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val listCategories: ListAdminCategoriesUc,
    val getCategory: GetAdminCategoryUc,
    val createCategory: CreateCategoryUc,
    val updateCategory: UpdateCategoryUc,
    val deleteCategory: DeleteCategoryUc,
    val getColor: GetAdminColorUc,
    val createColor: CreateColorUc,
    val updateColor: UpdateColorUc,
    val deleteColor: DeleteColorUc,
    val listMaterials: ListAdminMaterialsUc,
    val getMaterial: GetAdminMaterialUc,
    val createMaterial: CreateMaterialUc,
    val updateMaterial: UpdateMaterialUc,
    val deleteMaterial: DeleteMaterialUc,
)

private const val CATEGORY_ID = "categoryId"
private const val COLOR_KEY = "colorKey"
private const val MATERIAL_ID = "materialId"

/**
 * The panel's catalog reference data (contract tag `Admin · Catalog`): categories, the palette and materials, all
 * `[ADMIN, CATALOG_MANAGER]`. Mounted under `/api/v1`. After a write the route answers with the thing as the panel
 * lists it, read again by a query.
 *
 * @param mediaUrls where category photos are served from; @param siteUrl the shop's address, for canonical links.
 */
fun Route.webV1AdminCatalog(useCases: AdminCatalogUseCases, mediaUrls: MediaUrls, siteUrl: String) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CATALOG_MANAGER) {
        // ============ categories ============

        get("/admin/categories") {
            call.respondResult(useCases.listCategories.execute(ListAdminCategoriesUc.Qry)) { categories ->
                categories.map { AdminCategoryHttpResponse.from(it, mediaUrls, siteUrl) }
            }
        }

        post("/admin/categories") {
            val request = call.receive<CategoryUpsertHttpRequest>()
            val id = useCases.createCategory.execute(CreateCategoryUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getCategory.execute(GetAdminCategoryUc.Qry(id)), HttpStatusCode.Created) {
                AdminCategoryHttpResponse.from(it, mediaUrls, siteUrl)
            }
        }

        put("/admin/categories/{$CATEGORY_ID}") {
            val request = call.receive<CategoryUpsertHttpRequest>()
            val command = UpdateCategoryUc.Cmd(call.pathParameters[CATEGORY_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateCategory.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getCategory.execute(GetAdminCategoryUc.Qry(id))) { AdminCategoryHttpResponse.from(it, mediaUrls, siteUrl) }
        }

        delete("/admin/categories/{$CATEGORY_ID}") {
            call.respondResult(useCases.deleteCategory.execute(DeleteCategoryUc.Cmd(call.pathParameters[CATEGORY_ID].orEmpty(), call.panelActor())))
        }

        // ============ the palette (the shop's GET /colors lists it) ============

        post("/admin/colors") {
            val request = call.receive<ColorUpsertHttpRequest>()
            val key = useCases.createColor.execute(CreateColorUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getColor.execute(GetAdminColorUc.Qry(key)), HttpStatusCode.Created) { ColorHttpResponse.from(it) }
        }

        put("/admin/colors/{$COLOR_KEY}") {
            val request = call.receive<ColorUpsertHttpRequest>()
            val command = UpdateColorUc.Cmd(call.pathParameters[COLOR_KEY].orEmpty(), request.toFields(), call.panelActor())
            val key = useCases.updateColor.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getColor.execute(GetAdminColorUc.Qry(key))) { ColorHttpResponse.from(it) }
        }

        delete("/admin/colors/{$COLOR_KEY}") {
            call.respondResult(useCases.deleteColor.execute(DeleteColorUc.Cmd(call.pathParameters[COLOR_KEY].orEmpty(), call.panelActor())))
        }

        // ============ materials ============

        get("/admin/materials") {
            call.respondResult(useCases.listMaterials.execute(ListAdminMaterialsUc.Qry)) { materials -> materials.map(MaterialHttpResponse::from) }
        }

        post("/admin/materials") {
            val request = call.receive<MaterialUpsertHttpRequest>()
            val id = useCases.createMaterial.execute(CreateMaterialUc.Cmd(request.toFields(), call.panelActor()))
                .unwrapOr { return@post call.respondFailure(it) }
            call.respondResult(useCases.getMaterial.execute(GetAdminMaterialUc.Qry(id)), HttpStatusCode.Created) { MaterialHttpResponse.from(it) }
        }

        put("/admin/materials/{$MATERIAL_ID}") {
            val request = call.receive<MaterialUpsertHttpRequest>()
            val command = UpdateMaterialUc.Cmd(call.pathParameters[MATERIAL_ID].orEmpty(), request.toFields(), call.panelActor())
            val id = useCases.updateMaterial.execute(command).unwrapOr { return@put call.respondFailure(it) }
            call.respondResult(useCases.getMaterial.execute(GetAdminMaterialUc.Qry(id))) { MaterialHttpResponse.from(it) }
        }

        delete("/admin/materials/{$MATERIAL_ID}") {
            call.respondResult(useCases.deleteMaterial.execute(DeleteMaterialUc.Cmd(call.pathParameters[MATERIAL_ID].orEmpty(), call.panelActor())))
        }
    }
}
