package com.kiva.domain.aggregate.geo.entity

import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId

/**
 * A city of one province, the second choice of an address. A city belongs to exactly one province, and an address
 * may only pair a province with one of its own cities.
 *
 * Reference data, written only by migrations (V11__geo.sql), like [Province].
 *
 * Its identity is [id], which is unique across the country.
 *
 * @property sortOrder position in its province's list, smallest first (Persian alphabetical order).
 */
class City private constructor(
    val id: CityId,
    val provinceId: ProvinceId,
    val name: String,
    val sortOrder: Int,
) {
    init {
        require(name.isNotBlank()) { "A city needs a name (id ${id.value})" }
    }

    override fun equals(other: Any?): Boolean = other is City && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "City(id=${id.value}, provinceId=${provinceId.value})"

    companion object {
        fun constructFromPersisted(id: CityId, provinceId: ProvinceId, name: String, sortOrder: Int): City =
            City(id, provinceId, name, sortOrder)
    }
}
