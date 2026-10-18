package com.kiva.application.usecase.tracking

import com.kiva.common.valueobject.IranianMobileNumber
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DailyShipmentMaskTest {

    private val phone = IranianMobileNumber.constructFromPersisted("09121234821")

    @Test
    fun `a name keeps each word's first letter only`() {
        assertEquals("س*** م***", DailyShipmentMask.name("سارا محمدی"))
        assertEquals("ع*** ر*** ک***", DailyShipmentMask.name("  علی   رضا  کریمی "))
        assertEquals("س", DailyShipmentMask.initial(" سارا محمدی"))
        assertEquals("", DailyShipmentMask.initial("   "))
    }

    @Test
    fun `a phone keeps its last four digits only`() {
        assertEquals("4821", DailyShipmentMask.phoneLast4(phone))
        assertEquals("09** *** 4821", DailyShipmentMask.phone(phone))
    }
}
