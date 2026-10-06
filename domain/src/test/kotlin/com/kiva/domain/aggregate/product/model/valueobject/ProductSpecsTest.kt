package com.kiva.domain.aggregate.product.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ProductSpecsTest {

    @Nested
    inner class DimensionsTests {
        @Test
        fun `length, height and width in centimeters`() {
            val hobo = Dimensions.makeNew(34.0, 26.0, 12.0).getOrNull()!!
            assertEquals(Triple(34.0, 26.0, 12.0), Triple(hobo.lengthCm, hobo.heightCm, hobo.widthCm))
        }

        @ParameterizedTest
        @ValueSource(doubles = [0.0, -1.0, 200.5, Double.NaN, Double.POSITIVE_INFINITY])
        fun `zero, negative, over 2 meters or not a number is DIMENSIONS_INVALID`(bad: Double) {
            assertEquals(Result.failure(Dimensions.Failure.Invalid), Dimensions.makeNew(34.0, bad, 12.0))
        }

        @Test
        fun `a broken stored value is a bug, so constructFromPersisted throws`() {
            assertFailsWith<IllegalArgumentException> { Dimensions.constructFromPersisted(34.0, 0.0, 12.0) }
        }
    }

    // The spec of the prototype's hobo bags (kiva.js SPEC.hobo).
    private fun hobo(
        weightGrams: Int? = 650,
        strap: String? = "بند دوشی ثابت ۵۵ سانتی‌متر",
        pockets: String? = "۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل",
    ) = ProductSpecs.makeNew(
        dimensions = Dimensions.makeNew(34.0, 26.0, 12.0).getOrNull(),
        weightGrams = weightGrams,
        closure = ClosureType.ZIPPER,
        strap = strap,
        pockets = pockets,
        occasions = setOf(Occasion.DAILY, Occasion.UNIVERSITY, Occasion.WORK),
    )

    @Test
    fun `a full spec is kept as given`() {
        val specs = hobo().getOrNull()!!

        assertEquals(650, specs.weightGrams)
        assertEquals(ClosureType.ZIPPER, specs.closure)
        assertEquals("بند دوشی ثابت ۵۵ سانتی‌متر", specs.strap)
        assertEquals(setOf(Occasion.DAILY, Occasion.UNIVERSITY, Occasion.WORK), specs.occasions)
    }

    @Test
    fun `everything is optional, and blank texts mean not given`() {
        val specs = hobo(weightGrams = null, strap = "  ", pockets = null).getOrNull()!!

        assertNull(specs.weightGrams)
        assertNull(specs.strap)
        assertEquals(ProductSpecs.NONE, ProductSpecs.makeNew(null, null, null, null, null, emptySet()).getOrNull())
    }

    @Test
    fun `every problem is reported at once, each under its own field`() {
        val failure = hobo(weightGrams = 0, strap = "ب".repeat(201), pockets = "ج".repeat(201)).failureOrNull()!!

        assertEquals("SPECS_INVALID", failure.code)
        assertEquals("specs", failure.field)
        assertEquals(
            listOf("WEIGHT_INVALID" to "weightGrams", "STRAP_TOO_LONG" to "strap", "POCKETS_TOO_LONG" to "pockets"),
            failure.causes.map { it.code to it.field },
        )
    }

    @Test
    fun `no bag weighs more than 20 kg`() {
        assertEquals(20_000, hobo(weightGrams = 20_000).getOrNull()?.weightGrams)
        assertEquals("WEIGHT_INVALID", hobo(weightGrams = 20_001).failureOrNull()?.causes?.single()?.code)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { ProductSpecs.constructFromPersisted(null, -5, null, null, null, emptySet()) }
        assertFailsWith<IllegalArgumentException> { ProductSpecs.constructFromPersisted(null, null, null, " ", null, emptySet()) }
    }
}
