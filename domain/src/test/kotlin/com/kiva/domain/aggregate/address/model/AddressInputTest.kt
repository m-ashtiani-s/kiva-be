package com.kiva.domain.aggregate.address.model

import com.kiva.common.railway.ResultFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AddressInputTest {

    private fun fieldsAndCodes(failure: ResultFailure?): List<String> {
        assertIs<AddressInput.Invalid>(failure)
        return failure.problems.map { "${it.field} ${it.code}" }
    }

    @Test
    fun `a correct address is kept, normalized, with every value`() {
        val input = AddressInput.makeNew(
            title = "خانه", provinceId = 8, cityId = 394, addressLine = " خيابان ولیعصر، پلاک ۱۲ ", plaque = "۱۲", unit = null,
            postalCode = "۱۹۶۵۸-۴۳۱۱۷", recipientName = "سارا محمدی", recipientPhone = "۰۹۱۲۳۴۵۶۷۸۹", isSelfRecipient = null,
            lat = 35.75, lng = 51.41,
        ).getOrNull()!!

        assertEquals("خانه", input.title?.value)
        assertEquals(8 to 394, input.provinceId.value to input.cityId.value)
        assertEquals("خیابان ولیعصر، پلاک ۱۲", input.line.value)
        assertEquals("12", input.plaque?.value)
        assertEquals(null, input.unit)
        assertEquals("1965843117", input.postalCode.value)
        assertEquals("09123456789", input.recipientPhone.value)
        assertEquals(false, input.isSelfRecipient)
        assertEquals(35.75 to 51.41, input.location?.let { it.lat to it.lng })
        assertEquals("AddressInput(provinceId=8, cityId=394)", input.toString())
    }

    @Test
    fun `every wrong field comes back at once, each on its own field`() {
        val failure = AddressInput.makeNew(
            title = "خ".repeat(31), provinceId = 0, cityId = -3, addressLine = "کوتاه", plaque = "<12>", unit = "12345678901",
            postalCode = "123", recipientName = "سا", recipientPhone = "0912", isSelfRecipient = true, lat = 35.7, lng = null,
        ).failureOrNull()

        assertEquals(
            listOf(
                "title ADDRESS_TITLE_TOO_LONG",
                "provinceId PROVINCE_ID_INVALID",
                "cityId CITY_ID_INVALID",
                "addressLine ADDRESS_LINE_TOO_SHORT",
                "plaque PLAQUE_INVALID",
                "unit UNIT_TOO_LONG",
                "postalCode POSTAL_CODE_INVALID",
                "recipientName RECIPIENT_NAME_TOO_SHORT",
                "recipientPhone PHONE_INVALID",
                "location LOCATION_INVALID",
            ),
            fieldsAndCodes(failure),
        )
    }

    @Test
    fun `the contract's required fields, missing, fail like empty ones`() {
        val failure = AddressInput.makeNew(null, null, null, null, null, null, null, null, null, null, null, null).failureOrNull()

        assertEquals(
            listOf(
                "provinceId PROVINCE_REQUIRED",
                "cityId CITY_REQUIRED",
                "addressLine ADDRESS_LINE_TOO_SHORT",
                "postalCode POSTAL_CODE_INVALID",
                "recipientName RECIPIENT_NAME_REQUIRED",
                "recipientPhone PHONE_REQUIRED",
            ),
            fieldsAndCodes(failure),
        )
    }
}
