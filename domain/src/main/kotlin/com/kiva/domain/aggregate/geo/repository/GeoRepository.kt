package com.kiva.domain.aggregate.geo.repository

import com.kiva.domain.aggregate.geo.entity.City
import com.kiva.domain.aggregate.geo.entity.Province
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId

/** Reads the province and city lists. Read only: the lists change only through migrations. */
interface GeoRepository {
    /** Every province, in list order ([Province.sortOrder]). */
    fun findAllProvinces(): List<Province>

    /** Every city of the country, province by province, each province's cities in their list order. */
    fun findAllCities(): List<City>

    fun findProvince(id: ProvinceId): Province?

    /** The cities of one province in list order ([City.sortOrder]); empty for a province that doesn't exist. */
    fun findCitiesOf(provinceId: ProvinceId): List<City>
}
