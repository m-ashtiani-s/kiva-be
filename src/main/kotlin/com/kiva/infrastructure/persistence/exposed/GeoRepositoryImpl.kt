package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import com.kiva.domain.aggregate.geo.repository.GeoRepository
import com.kiva.infrastructure.persistence.exposed.tbl.CityTbl
import com.kiva.infrastructure.persistence.exposed.tbl.ProvinceTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Runs inside the use case's transaction; it never opens one itself. */
class GeoRepositoryImpl : GeoRepository {

    // The id breaks ties, so two rows with the same sortOrder still come back in a stable order.
    override fun findAllProvinces(): List<Province> =
        ProvinceTbl.selectAll()
            .orderBy(ProvinceTbl.sortOrder to SortOrder.ASC, ProvinceTbl.provinceId to SortOrder.ASC)
            .map { it.toProvince() }

    override fun findAllCities(): List<City> =
        CityTbl.selectAll()
            .orderBy(CityTbl.provinceId to SortOrder.ASC, CityTbl.sortOrder to SortOrder.ASC, CityTbl.cityId to SortOrder.ASC)
            .map { it.toCity() }

    override fun findProvince(id: ProvinceId): Province? =
        ProvinceTbl.selectAll()
            .where { ProvinceTbl.provinceId eq id.value }
            .singleOrNull()
            ?.toProvince()

    override fun findCitiesOf(provinceId: ProvinceId): List<City> =
        CityTbl.selectAll()
            .where { CityTbl.provinceId eq provinceId.value }
            .orderBy(CityTbl.sortOrder to SortOrder.ASC, CityTbl.cityId to SortOrder.ASC)
            .map { it.toCity() }

    private fun ResultRow.toProvince(): Province =
        Province.constructFromPersisted(
            id = ProvinceId.constructFromPersisted(this[ProvinceTbl.provinceId]),
            name = this[ProvinceTbl.name],
            sortOrder = this[ProvinceTbl.sortOrder],
        )

    private fun ResultRow.toCity(): City =
        City.constructFromPersisted(
            id = CityId.constructFromPersisted(this[CityTbl.cityId]),
            provinceId = ProvinceId.constructFromPersisted(this[CityTbl.provinceId]),
            name = this[CityTbl.name],
            sortOrder = this[CityTbl.sortOrder],
        )
}
