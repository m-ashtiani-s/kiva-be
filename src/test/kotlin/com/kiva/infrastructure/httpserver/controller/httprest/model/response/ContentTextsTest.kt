package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.persistence.exposed.StoredSettings
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The Persian texts `GET /config` builds (roadmap phase 5, decisions 16 and 23). */
class ContentTextsTest {

    @Test
    fun `a landline shows as its area code and the rest, a mobile in three parts, in Persian digits`() {
        assertEquals("۰۲۱-۹۱۰۰۱۲۳۴", ContentTexts.phoneDisplay("02191001234"))
        assertEquals("۰۹۱۲-۳۴۵-۶۷۸۹", ContentTexts.phoneDisplay("09123456789"))
    }

    @Test
    fun `a text's settings are filled in Persian digits - the threshold with separators - and other braces stay`() {
        // The test database's settings: 4 days of reservation, 7 to return, free shipping from 3,000,000.
        val settings: ShopSettings = StoredSettings.read(TestDatabase.database)

        assertEquals(
            "رزرو ۴ روزه، ۷ روز بازگشت، ارسال رایگان بالای ۳٬۰۰۰٬۰۰۰ تومان {other}",
            ContentTexts.fill("رزرو {holdDays} روزه، {returnWindowDays} روز بازگشت، ارسال رایگان بالای {freeShippingThreshold} تومان {other}", settings),
        )
    }
}
