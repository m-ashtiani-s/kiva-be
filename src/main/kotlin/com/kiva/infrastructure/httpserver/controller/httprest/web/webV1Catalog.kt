package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.catalog.usecase.query.GetCategoryUc
import com.kiva.application.usecase.catalog.usecase.query.ListCategoriesUc
import com.kiva.application.usecase.catalog.usecase.query.ListColorsUc
import com.kiva.application.usecase.catalog.usecase.query.ListMaterialsUc
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CategoryHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ColorHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MaterialHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Catalog reference data: colors, materials, categories (contract tag `Catalog`). Mounted under `/api/v1`.
 * The status of each failure comes from its kind (see respondResult), so there is no failure mapping here.
 */
fun Route.webV1Catalog(
    listColorsUc: ListColorsUc,
    listMaterialsUc: ListMaterialsUc,
    listCategoriesUc: ListCategoriesUc,
    getCategoryUc: GetCategoryUc,
) {
    get("/colors") {
        call.respondResult(listColorsUc.execute(ListColorsUc.Qry)) { colors -> colors.map(ColorHttpResponse::from) }
    }

    get("/materials") {
        call.respondResult(listMaterialsUc.execute(ListMaterialsUc.Qry)) { materials -> materials.map(MaterialHttpResponse::from) }
    }

    get("/categories") {
        call.respondResult(listCategoriesUc.execute(ListCategoriesUc.Qry)) { categories -> categories.map(CategoryHttpResponse::from) }
    }

    get("/categories/{slug}") {
        // Always present: the route only matches when the segment is there.
        val slug = call.pathParameters["slug"].orEmpty()
        call.respondResult(getCategoryUc.execute(GetCategoryUc.Qry(slug))) { CategoryHttpResponse.from(it) }
    }
}
