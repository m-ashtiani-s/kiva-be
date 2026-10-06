package com.kiva.domain.aggregate.material.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class MaterialTest {

    private fun material(id: Long, name: String = "چرم طبیعی گاوی", care: List<String> = emptyList()) =
        Material.constructFromPersisted(
            id = MaterialId.constructFromPersisted(id),
            slug = Slug.constructFromPersisted("genuine-cow-leather"),
            name = MaterialName.constructFromPersisted(name),
            family = MaterialFamily.GENUINE_LEATHER,
            description = null,
            careInstructions = care,
        )

    @Test
    fun `two materials with the same id are the same material, even after a rename`() {
        assertEquals(material(1), material(1, name = "چرم گاوی"))
        assertEquals(material(1).hashCode(), material(1, name = "چرم گاوی").hashCode())
    }

    @Test
    fun `materials with different ids are different materials`() {
        assertNotEquals(material(1), material(2))
    }

    @Test
    fun `care instructions are copied, so changing the caller's list later does not change the material`() {
        val care = mutableListOf("با دستمال نرم تمیز شود")
        val material = material(1, care = care)

        care += "از الکل استفاده نشود"

        assertEquals(listOf("با دستمال نرم تمیز شود"), material.careInstructions)
    }
}
