package com.kiva.infrastructure.persistence.exposed

import com.kiva.domain.aggregate.material.model.valueobject.MaterialFamily
import com.kiva.infrastructure.TestDatabase
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Reads the materials that V2__catalog_reference_data.sql seeds into the freshly migrated kiva_test. */
class MaterialRepositoryImplTest {

    private val db = TestDatabase.database
    private val repository = MaterialRepositoryImpl()

    @Test
    fun `the 12 seeded materials come back in the order they were added, with their family`() {
        val materials = transaction(db) { repository.findAll().map { it.slug.value to it.family } }

        assertEquals(
            listOf(
                "genuine-cow-leather" to MaterialFamily.GENUINE_LEATHER,
                "genuine-goat-leather" to MaterialFamily.GENUINE_LEATHER,
                "genuine-leather" to MaterialFamily.GENUINE_LEATHER,
                "faux-leather-matte" to MaterialFamily.FAUX_LEATHER,
                "faux-leather-soft" to MaterialFamily.FAUX_LEATHER,
                "faux-leather-glossy" to MaterialFamily.FAUX_LEATHER,
                "faux-leather" to MaterialFamily.FAUX_LEATHER,
                "waterproof-canvas" to MaterialFamily.CANVAS,
                "thick-cotton" to MaterialFamily.COTTON,
                "faux-suede" to MaterialFamily.FAUX_SUEDE,
                "satin" to MaterialFamily.SATIN,
                "velvet" to MaterialFamily.VELVET,
            ),
            materials,
        )
    }

    @Test
    fun `every column is mapped, an empty text array included`() {
        val matte = transaction(db) { repository.findAll().single { it.slug.value == "faux-leather-matte" } }

        assertEquals(4L, matte.id.value)
        assertEquals("چرم مصنوعی مات درجه یک", matte.name.value)
        assertNull(matte.description)
        assertEquals(emptyList(), matte.careInstructions)
    }
}
