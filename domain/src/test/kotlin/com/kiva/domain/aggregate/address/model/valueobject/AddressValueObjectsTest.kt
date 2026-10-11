package com.kiva.domain.aggregate.address.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AddressValueObjectsTest {

    @Nested
    inner class Line {
        @Test
        fun `letters and spaces are normalized but the digits stay as typed`() {
            assertEquals("خیابان ولیعصر، کوچه نسترن، پلاک ۱۲", AddressLine.makeNew("  خيابان ولیعصر،  کوچه نسترن، پلاک ۱۲ ").getOrNull()?.value)
        }

        @Test
        fun `10 to 300 characters after trimming`() {
            assertEquals(Result.failure(AddressLine.Failure.TooShort), AddressLine.makeNew("  کوچه ۱۲  "))
            assertEquals(10, AddressLine.makeNew("کوچه نسترن").getOrNull()?.value?.length)
            assertEquals(Result.failure(AddressLine.Failure.TooLong), AddressLine.makeNew("ا".repeat(301)))
            assertEquals("addressLine", AddressLine.Failure.TooShort.field)
            assertEquals(mapOf<String, Any>("minLength" to 10), AddressLine.Failure.TooShort.meta)
        }

        @Test
        fun `angle brackets and control characters are refused`() {
            assertEquals(Result.failure(AddressLine.Failure.InvalidCharacters), AddressLine.makeNew("خیابان <b>ولیعصر</b>"))
            assertEquals(Result.failure(AddressLine.Failure.InvalidCharacters), AddressLine.makeNew("خیابان ولیعصر\u0000 کوچه"))
            assertEquals("ADDRESS_LINE_INVALID", AddressLine.Failure.InvalidCharacters.code)
        }

        @Test
        fun `a new line typed in the form is just a space`() {
            assertEquals("خیابان ولیعصر کوچه نسترن", AddressLine.makeNew("خیابان ولیعصر\nکوچه نسترن").getOrNull()?.value)
        }

        @Test
        fun `it never shows itself, and a broken stored value throws`() {
            assertEquals("AddressLine(***)", AddressLine.makeNew("خیابان ولیعصر، پلاک ۱۲").getOrNull().toString())
            assertFailsWith<IllegalArgumentException> { AddressLine.constructFromPersisted("کوتاه") }
        }
    }

    @Nested
    inner class Postal {
        @ParameterizedTest
        @ValueSource(strings = ["1965843117", "۱۹۶۵۸-۴۳۱۱۷", " 19658 43117 ", "١٩٦٥٨٤٣١١٧"])
        fun `Persian or Arabic digits, spaces and a dash are one code`(raw: String) {
            assertEquals("1965843117", PostalCode.makeNew(raw).getOrNull()?.value)
        }

        @ParameterizedTest
        @ValueSource(strings = ["196584311", "19658431170", "19658a3117", ""])
        fun `anything but 10 digits is POSTAL_CODE_INVALID on postalCode`(raw: String) {
            assertEquals(Result.failure(PostalCode.Failure.Invalid), PostalCode.makeNew(raw))
            assertEquals("postalCode", PostalCode.Failure.Invalid.field)
        }

        @Test
        fun `it never shows itself`() {
            assertEquals("PostalCode(***)", PostalCode.makeNew("1965843117").getOrNull().toString())
        }
    }

    @Nested
    inner class Recipient {
        @Test
        fun `a name of 3 to 80 characters, normalized`() {
            assertEquals("سارا محمدی", RecipientName.makeNew(" سارا  محمدي ").getOrNull()?.value)
        }

        @Test
        fun `each wrong name has its own code on recipientName`() {
            assertEquals(Result.failure(RecipientName.Failure.Blank), RecipientName.makeNew("  "))
            assertEquals(Result.failure(RecipientName.Failure.TooShort), RecipientName.makeNew("سا"))
            assertEquals(Result.failure(RecipientName.Failure.TooLong), RecipientName.makeNew("س".repeat(81)))
            assertEquals(Result.failure(RecipientName.Failure.InvalidCharacters), RecipientName.makeNew("سارا <script>"))
            assertEquals("recipientName", RecipientName.Failure.Blank.field)
        }

        @Test
        fun `it never shows itself`() {
            assertEquals("RecipientName(***)", RecipientName.makeNew("سارا محمدی").getOrNull().toString())
        }
    }

    @Nested
    inner class OptionalTexts {
        @Test
        fun `missing or blank is none`() {
            assertEquals(Result.success(null), AddressTitle.makeNew(null))
            assertEquals(Result.success(null), AddressTitle.makeNew("  "))
            assertEquals(Result.success(null), Plaque.makeNew(""))
            assertEquals(Result.success(null), UnitNumber.makeNew(null))
        }

        @Test
        fun `a title keeps its digits, a plaque and a unit are stored with English digits`() {
            assertEquals("خانه ۲", AddressTitle.makeNew("خانه ۲").getOrNull()?.value)
            assertEquals("12/1", Plaque.makeNew("۱۲/۱").getOrNull()?.value)
            assertEquals("4", UnitNumber.makeNew(" ۴ ").getOrNull()?.value)
        }

        @Test
        fun `too long or with angle brackets is refused, each on its own field`() {
            assertEquals(Result.failure(AddressTitle.Failure.TooLong), AddressTitle.makeNew("خ".repeat(31)))
            assertEquals(Result.failure(Plaque.Failure.TooLong), Plaque.makeNew("12345678901"))
            assertEquals(Result.failure(UnitNumber.Failure.InvalidCharacters), UnitNumber.makeNew("<4>"))
            assertEquals(listOf("title", "plaque", "unit"), listOf(AddressTitle.Failure.TooLong.field, Plaque.Failure.TooLong.field, UnitNumber.Failure.TooLong.field))
        }
    }

    @Nested
    inner class Point {
        @Test
        fun `both coordinates make a point, neither makes none`() {
            assertEquals(GeoPoint.constructFromPersisted(35.7575, 51.4107), GeoPoint.makeNew(35.7575, 51.4107).getOrNull())
            assertEquals(Result.success(null), GeoPoint.makeNew(null, null))
        }

        @Test
        fun `only one of them, or one out of range, is LOCATION_INVALID on location`() {
            assertEquals(Result.failure(GeoPoint.Failure.Invalid), GeoPoint.makeNew(35.7, null))
            assertEquals(Result.failure(GeoPoint.Failure.Invalid), GeoPoint.makeNew(91.0, 51.4))
            assertEquals(Result.failure(GeoPoint.Failure.Invalid), GeoPoint.makeNew(35.7, -181.0))
            assertEquals(Result.failure(GeoPoint.Failure.Invalid), GeoPoint.makeNew(Double.NaN, 51.4))
            assertEquals("location", GeoPoint.Failure.Invalid.field)
        }

        @Test
        fun `it never shows where someone lives`() {
            assertEquals("GeoPoint(***)", GeoPoint.constructFromPersisted(35.7, 51.4).toString())
        }
    }

    @Nested
    inner class Id {
        @Test
        fun `a positive number is an id, zero or negative is ADDRESS_ID_INVALID`() {
            assertEquals(42L, AddressId.makeNew(42).getOrNull()?.value)
            assertEquals(Result.failure(AddressId.Failure.Invalid), AddressId.makeNew(0))
            assertNull(AddressId.Failure.Invalid.field)
            assertFailsWith<IllegalArgumentException> { AddressId.constructFromPersisted(-1) }
        }
    }
}
