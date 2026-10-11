package com.kiva.domain.aggregate.geo.entity

import com.kiva.domain.aggregate.geo.model.valueobject.CityId
import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CityTest {

    private fun city(id: Int, provinceId: Int = 8, name: String = "تهران", sortOrder: Int = 15) =
        City.constructFromPersisted(CityId.constructFromPersisted(id), ProvinceId.constructFromPersisted(provinceId), name, sortOrder)

    @Test
    fun `two cities with the same id are the same city, even if the name was corrected`() {
        val before = city(966, provinceId = 24, name = "علی اباد")
        val after = city(966, provinceId = 24, name = "علی آباد")

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `cities with the same name in two provinces are different cities`() {
        assertNotEquals(city(501, provinceId = 17, name = "حسن آباد"), city(502, provinceId = 4, name = "حسن آباد"))
    }

    @Test
    fun `constructFromPersisted keeps every value`() {
        val tehran = city(394)

        assertEquals(394, tehran.id.value)
        assertEquals(8, tehran.provinceId.value)
        assertEquals("تهران", tehran.name)
        assertEquals(15, tehran.sortOrder)
        assertEquals("City(id=394, provinceId=8)", tehran.toString())
    }

    @Test
    fun `a stored city without a name is a bug, so it throws`() {
        assertFailsWith<IllegalArgumentException> { city(394, name = "") }
    }
}
