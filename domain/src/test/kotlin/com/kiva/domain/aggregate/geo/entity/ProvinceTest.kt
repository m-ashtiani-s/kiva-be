package com.kiva.domain.aggregate.geo.entity

import com.kiva.domain.aggregate.geo.model.valueobject.ProvinceId
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ProvinceTest {

    private fun province(id: Int, name: String = "تهران", sortOrder: Int = 8) =
        Province.constructFromPersisted(ProvinceId.constructFromPersisted(id), name, sortOrder)

    @Test
    fun `two provinces with the same id are the same province, even if the name was corrected`() {
        val before = province(16, name = "سیستان وبلوچستان")
        val after = province(16, name = "سیستان و بلوچستان")

        assertEquals(before, after)
        assertEquals(before.hashCode(), after.hashCode())
    }

    @Test
    fun `provinces with different ids are different provinces`() {
        assertNotEquals(province(8), province(4, name = "تهران"))
    }

    @Test
    fun `constructFromPersisted keeps every value`() {
        val tehran = province(8)

        assertEquals(8, tehran.id.value)
        assertEquals("تهران", tehran.name)
        assertEquals(8, tehran.sortOrder)
        assertEquals("Province(id=8)", tehran.toString())
    }

    @Test
    fun `a stored province without a name is a bug, so it throws`() {
        assertFailsWith<IllegalArgumentException> { province(8, name = "  ") }
    }
}
