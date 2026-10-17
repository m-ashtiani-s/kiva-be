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
        straps: List<StrapInfo> = listOf(StrapInfo.legacy("بند دوشی ثابت ۵۵ سانتی‌متر")),
        stitching: String? = "دوخت دولایه",
        madeIn: String? = "ایران",
        laptopMaxInch: Double? = null,
    ) = ProductSpecs.makeNew(
        dimensions = Dimensions.makeNew(34.0, 26.0, 12.0).getOrNull(),
        weightGrams = weightGrams,
        closure = ClosureType.ZIPPER,
        straps = straps,
        pockets = PocketInfo.legacy("۱ جیب زیپ‌دار داخلی، ۲ جیب موبایل"),
        laptopMaxInch = laptopMaxInch,
        occasions = setOf(Occasion.DAILY, Occasion.UNIVERSITY, Occasion.WORK),
        stitching = stitching,
        madeIn = madeIn,
    )

    @Test
    fun `a full spec is kept as given`() {
        val specs = hobo().getOrNull()!!

        assertEquals(650, specs.weightGrams)
        assertEquals(ClosureType.ZIPPER, specs.closure)
        assertEquals("بند دوشی ثابت ۵۵ سانتی‌متر", specs.straps.single().label)
        assertEquals("ایران", specs.madeIn)
        assertEquals(setOf(Occasion.DAILY, Occasion.UNIVERSITY, Occasion.WORK), specs.occasions)
    }

    @Test
    fun `everything is optional, and blank texts mean not given`() {
        val specs = hobo(weightGrams = null, stitching = "  ", madeIn = "").getOrNull()!!

        assertNull(specs.weightGrams)
        assertNull(specs.stitching)
        assertNull(specs.madeIn)
        assertEquals(ProductSpecs.NONE, ProductSpecs.makeNew().getOrNull())
    }

    @Test
    fun `every limit on the whole is reported at once, each under its own field`() {
        val failure = hobo(
            weightGrams = 0,
            straps = List(5) { StrapInfo.legacy("بند") },
            stitching = "د".repeat(101),
            madeIn = "ا".repeat(41),
            laptopMaxInch = 21.0,
        ).failureOrNull()!!

        assertEquals(
            listOf(
                "WEIGHT_INVALID" to "weightGrams",
                "STRAPS_TOO_MANY" to "straps",
                "LAPTOP_MAX_INCH_INVALID" to "laptopMaxInch",
                "STITCHING_TOO_LONG" to "stitching",
                "MADE_IN_TOO_LONG" to "madeIn",
            ),
            failure.causes.map { it.code to it.field },
        )
    }

    @Test
    fun `problemsOfWhole gives the same limits without the parts`() {
        assertEquals(
            listOf("WEIGHT_INVALID", "CAPACITY_LITERS_INVALID"),
            ProductSpecs.problemsOfWhole(20_001, 4, 14.0, 0.0, null, null).map { it.code },
        )
    }

    @Test
    fun `no bag weighs more than 20 kg`() {
        assertEquals(20_000, hobo(weightGrams = 20_000).getOrNull()?.weightGrams)
        assertEquals("WEIGHT_INVALID", hobo(weightGrams = 20_001).failureOrNull()?.causes?.single()?.code)
    }

    @Test
    fun `a broken stored value is a bug, so constructFromPersisted throws`() {
        assertFailsWith<IllegalArgumentException> { ProductSpecs.constructFromPersisted(weightGrams = -5) }
        assertFailsWith<IllegalArgumentException> { ProductSpecs.constructFromPersisted(stitching = " ") }
        assertFailsWith<IllegalArgumentException> { ProductSpecs.constructFromPersisted(straps = List(5) { StrapInfo.legacy("بند") }) }
    }
}
