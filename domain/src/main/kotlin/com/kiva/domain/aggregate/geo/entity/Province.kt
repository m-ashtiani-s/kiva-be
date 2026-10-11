package com.kiva.domain.aggregate.geo.entity

import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId

/**
 * One of Iran's 31 provinces (تهران، اصفهان، ...), the first choice of an address.
 *
 * Reference data, written only by migrations (V11__geo.sql): there is no way to create or edit a province in the
 * application, so the name is a plain text read back from the table.
 *
 * Its identity is [id]: two provinces with the same id are the same province, whatever their other values.
 *
 * @property sortOrder position in the list, smallest first (Persian alphabetical order).
 */
class Province private constructor(
    val id: ProvinceId,
    val name: String,
    val sortOrder: Int,
) {
    init {
        require(name.isNotBlank()) { "A province needs a name (id ${id.value})" }
    }

    override fun equals(other: Any?): Boolean = other is Province && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Province(id=${id.value})"

    companion object {
        fun constructFromPersisted(id: ProvinceId, name: String, sortOrder: Int): Province = Province(id, name, sortOrder)
    }
}
