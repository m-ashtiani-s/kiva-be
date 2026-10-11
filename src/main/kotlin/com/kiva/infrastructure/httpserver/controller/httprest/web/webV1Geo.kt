package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.geo.usecase.query.ListCitiesOfProvinceUc
import com.kiva.application.usecase.geo.usecase.query.ListProvincesUc
import com.kiva.infrastructure.common.http.respondResultCacheable
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.CityHttpResponse
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.ProvinceHttpResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Provinces and cities for the address form (contract tag `Addresses`). Mounted under `/api/v1`, no login needed.
 * The lists change only with a migration, so both answers carry an ETag and may be cached for five minutes.
 */
fun Route.webV1Geo(
    listProvincesUc: ListProvincesUc,
    listCitiesOfProvinceUc: ListCitiesOfProvinceUc,
) {
    get("/geo/provinces") {
        val query = ListProvincesUc.Qry(includeCities = call.request.queryParameters["includeCities"])
        call.respondResultCacheable(listProvincesUc.execute(query)) { provinces -> provinces.map(ProvinceHttpResponse::from) }
    }

    get("/geo/provinces/{provinceId}/cities") {
        // Always present: the route only matches when the segment is there.
        val query = ListCitiesOfProvinceUc.Qry(provinceId = call.pathParameters["provinceId"].orEmpty())
        call.respondResultCacheable(listCitiesOfProvinceUc.execute(query)) { cities -> cities.map(CityHttpResponse::from) }
    }
}
