package com.kiva.domain.aggregate.material.entity

import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.material.model.valueobject.CareInstructions
import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.domain.aggregate.material.model.valueobject.MaterialId
import com.kiva.domain.aggregate.material.model.valueobject.MaterialName
import com.kiva.domain.aggregate.material.model.valueobject.MaterialNote
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MaterialTest {

    private fun material(id: Long, name: String = "چرم طبیعی گاوی", care: List<String> = emptyList()) =
        Material.constructFromPersisted(
            id = MaterialId.constructFromPersisted(id),
            slug = Slug.constructFromPersisted("genuine-cow-leather"),
            name = MaterialName.constructFromPersisted(name),
            family = MaterialFamily.GENUINE_LEATHER,
            description = null,
            careInstructions = CareInstructions.constructFromPersisted(care),
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

        assertEquals(listOf("با دستمال نرم تمیز شود"), material.careInstructions.items)
    }

    @Test
    fun `create keeps every value it is given`() {
        val material = Material.create(
            MaterialId.constructFromPersisted(13), Slug.constructFromPersisted("vegan-cork"), MaterialName.constructFromPersisted("چوب‌پنبه"),
            MaterialFamily.OTHER, MaterialNote.constructFromPersisted("سبک و ضدآب"), CareInstructions.constructFromPersisted(listOf("خشک نگه دارید")),
        )

        assertEquals(13L, material.id.value)
        assertEquals("vegan-cork", material.slug.value)
        assertEquals(MaterialFamily.OTHER, material.family)
        assertEquals("سبک و ضدآب", material.description?.value)
        assertEquals(listOf("خشک نگه دارید"), material.careInstructions.items)
    }

    @Test
    fun `change replaces every value and keeps the id - the same values again are the same values`() {
        val before = material(1)

        val renamed = before.change(Slug.constructFromPersisted("cow-leather"), MaterialName.constructFromPersisted("چرم گاوی"), MaterialFamily.GENUINE_LEATHER, null, CareInstructions.NONE)
        val same = before.change(before.slug, before.name, before.family, before.description, before.careInstructions)

        assertEquals(before.id, renamed.id)
        assertEquals("cow-leather", renamed.slug.value)
        assertFalse(renamed.sameValuesAs(before))
        assertTrue(same.sameValuesAs(before))
    }
}
