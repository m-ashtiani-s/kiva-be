package com.kiva.domain.aggregate.product.model.valueobject

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpecPartsTest {

    @Nested
    inner class Straps {
        @Test
        fun `a strap keeps its kind, lengths and flags, texts trimmed`() {
            val strap = StrapInfo.makeNew("CROSSBODY", " بند بلند ", null, 80.0, 120.0, true, null, " چرم ").getOrNull()!!

            assertEquals(StrapType.CROSSBODY, strap.type)
            assertEquals("بند بلند", strap.label)
            assertEquals(80.0 to 120.0, strap.minLengthCm to strap.maxLengthCm)
            assertEquals(true to false, strap.adjustable to strap.detachable)
            assertEquals("چرم", strap.material)
        }

        @Test
        fun `every problem of a strap at once, each under its field`() {
            val failure = StrapInfo.makeNew("ROPE", "ب".repeat(201), 0.0, 130.0, 90.0, null, null, "چ".repeat(61)).failureOrNull()!!

            assertEquals(
                listOf(
                    "STRAP_TYPE_INVALID" to "type",
                    "STRAP_TOO_LONG" to "label",
                    "STRAP_LENGTH_INVALID" to "lengthCm",
                    "STRAP_LENGTH_RANGE_INVALID" to "maxLengthCm",
                    "STRAP_MATERIAL_TOO_LONG" to "material",
                ),
                failure.causes.map { it.code to it.field },
            )
        }

        @Test
        fun `a legacy strap is its label alone`() {
            val strap = StrapInfo.legacy("بند دوشی ثابت")
            assertEquals("بند دوشی ثابت", strap.label)
            assertNull(strap.type)
        }

        @Test
        fun `a broken stored strap is a bug`() {
            assertFailsWith<IllegalArgumentException> { StrapInfo.constructFromPersisted(null, null, 201.0, null, null, false, false, null) }
        }
    }

    @Nested
    inner class Pockets {
        @Test
        fun `missing counts are 0 and missing flags false`() {
            val pockets = PocketInfo.makeNew(1, null, null, 2, null, null, null, null, true, null).getOrNull()!!

            assertEquals(listOf(1, 0, 0, 2, 0, 0), listOf(pockets.internal, pockets.internalZip, pockets.external, pockets.phone, pockets.cardSlots, pockets.billSlots))
            assertEquals(true, pockets.laptopSleeve)
            assertNull(pockets.summary)
        }

        @Test
        fun `a count outside 0 to 20 and a long summary are each a problem`() {
            val failure = PocketInfo.makeNew(21, -1, null, null, null, null, null, null, null, "ج".repeat(201)).failureOrNull()!!

            assertEquals(
                listOf("POCKET_COUNT_INVALID" to "internal", "POCKET_COUNT_INVALID" to "internalZip", "POCKETS_TOO_LONG" to "summary"),
                failure.causes.map { it.code to it.field },
            )
        }
    }

    @Nested
    inner class SmallParts {
        @Test
        fun `lining and hardware with nothing in them are no info at all`() {
            assertNull(LiningInfo.makeNew(" ", null).getOrNull())
            assertNull(HardwareInfo.makeNew(null, "").getOrNull())
            assertEquals("طلایی مات", HardwareInfo.makeNew("فلز", " طلایی مات ").getOrNull()?.finish)
        }

        @Test
        fun `too long lining texts are reported under their fields`() {
            val failure = LiningInfo.makeNew("آ".repeat(61), "ر".repeat(41)).failureOrNull()!!
            assertEquals(listOf("material", "color"), failure.causes.map { it.field })
        }

        @Test
        fun `a warranty needs 1 to 60 months`() {
            assertEquals(6, WarrantyInfo.makeNew(6, "ضمانت دوخت").getOrNull()?.months)
            assertNull(WarrantyInfo.makeNew(null, null).getOrNull())
            assertEquals("WARRANTY_MONTHS_INVALID", WarrantyInfo.makeNew(61, null).failureOrNull()?.causes?.single()?.code)
            assertEquals("WARRANTY_MONTHS_INVALID", WarrantyInfo.makeNew(null, "بدون ماه").failureOrNull()?.causes?.single()?.code)
        }

        @Test
        fun `a fit item is one of the contract's`() {
            assertEquals(FitItem.LAPTOP_14, FitItem.makeNew("LAPTOP_14").getOrNull())
            assertEquals("FIT_ITEM_INVALID", FitItem.makeNew("laptop").failureOrNull()?.code)
        }
    }
}
