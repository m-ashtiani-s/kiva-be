package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.settings.SettingsFixtures.pathsOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SettingsValueObjectsTest {

    @Nested
    inner class Days {
        @Test
        fun `0 to 30 days, the most never under the least`() {
            assertEquals(DeliveryDays.constructFromPersisted(0, 0), DeliveryDays.makeNew(0, 0).getOrNull())
            assertEquals(DeliveryDays.constructFromPersisted(3, 30), DeliveryDays.makeNew(3, 30).getOrNull())
        }

        @Test
        fun `out of range is NUMBER_OUT_OF_RANGE on its own field, both at once`() {
            assertEquals(listOf("minDays NUMBER_OUT_OF_RANGE", "maxDays NUMBER_OUT_OF_RANGE"), pathsOf(DeliveryDays.makeNew(31, 40).failureOrNull()))
            val under = DeliveryDays.makeNew(3, 2).failureOrNull()
            assertEquals(listOf("maxDays NUMBER_OUT_OF_RANGE"), pathsOf(under))
            assertEquals(mapOf<String, Any>("min" to 3L, "max" to 30L), under?.causes?.single()?.meta)
            assertEquals(listOf("minDays FIELD_REQUIRED", "maxDays FIELD_REQUIRED"), pathsOf(DeliveryDays.makeNew(null, null).failureOrNull()))
        }
    }

    @Nested
    inner class Names {
        @Test
        fun `a method's name is trimmed, 1 to 50 letters`() {
            assertEquals("پست معمولی", ShippingMethodName.makeNew("  پست معمولی ").getOrNull()?.value)
            assertEquals(listOf("FIELD_REQUIRED"), pathsOf(ShippingMethodName.makeNew("  ").failureOrNull()))
            assertEquals(listOf("TEXT_TOO_LONG"), pathsOf(ShippingMethodName.makeNew("ا".repeat(51)).failureOrNull()))
        }

        @Test
        fun `an icon is lower case Latin, digits and dashes, up to 30`() {
            assertEquals("truck-fast", IconName.makeNew("truck-fast").getOrNull()?.value)
            listOf("Truck", "کامیون", "", "a".repeat(31), "box!").forEach { assertEquals(IconName.Failure.Invalid, IconName.makeNew(it).failureOrNull(), it) }
        }
    }

    @Nested
    inner class Tracking {
        @Test
        fun `an https page with {code}, up to 300`() {
            val template = TrackingUrlTemplate.makeNew(" https://tipaxco.com/tracking?code={code} ").getOrNull()!!
            assertEquals("https://tipaxco.com/tracking?code=TX123456", template.urlOf("TX123456"))
        }

        @Test
        fun `http, no {code}, a space or too long is TRACKING_URL_TEMPLATE_INVALID on trackingUrlTemplate`() {
            listOf("http://x.ir/{code}", "https://x.ir/track", "https://x.ir/a b/{code}", "https://x.ir/{code}" + "a".repeat(300)).forEach {
                val failure = TrackingUrlTemplate.makeNew(it).failureOrNull()
                assertEquals("TRACKING_URL_TEMPLATE_INVALID", failure?.code, it)
                assertEquals("trackingUrlTemplate", failure?.field)
            }
        }
    }

    @Nested
    inner class Phone {
        @Test
        fun `a mobile or a landline with its area code, its digits made Latin and its dashes dropped`() {
            assertEquals("02191001234", SupportPhone.makeNew("۰۲۱-۹۱۰۰ ۱۲۳۴").getOrNull()?.value)
            assertEquals("09121234567", SupportPhone.makeNew("09121234567").getOrNull()?.value)
        }

        @Test
        fun `anything else is SUPPORT_PHONE_INVALID on supportPhone`() {
            listOf("91001234", "+982191001234", "00121234567", "0912123456", "tel").forEach {
                val failure = SupportPhone.makeNew(it).failureOrNull()
                assertEquals("SUPPORT_PHONE_INVALID", failure?.code, it)
                assertEquals("supportPhone", failure?.field)
            }
        }
    }

    @Nested
    inner class Hours {
        @Test
        fun `a line with its days and its hours, or closed`() {
            val open = SupportHours.makeNew(" شنبه تا چهارشنبه ", listOf("SATURDAY", "WEDNESDAY"), "09:00", "۲۱:۰۰", null).getOrNull()!!
            assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.WEDNESDAY), open.weekdays)
            assertEquals(LocalTime.of(21, 0), open.to)
            assertEquals(false, open.messengerOnly)

            val closed = SupportHours.makeNew("جمعه", listOf("FRIDAY"), null, null, true).getOrNull()!!
            assertNull(closed.from)
            assertEquals(true, closed.messengerOnly)
        }

        @Test
        fun `anything wrong with the line is SUPPORT_HOURS_INVALID, without a field of its own`() {
            listOf(
                SupportHours.makeNew("", listOf("FRIDAY"), null, null, null),
                SupportHours.makeNew("ا".repeat(51), listOf("FRIDAY"), null, null, null),
                SupportHours.makeNew("x", emptyList(), null, null, null),
                SupportHours.makeNew("x", listOf("FRIDAY", "FRIDAY"), null, null, null),
                SupportHours.makeNew("x", listOf("FRYDAY"), null, null, null),
                SupportHours.makeNew("x", listOf("FRIDAY"), "21:00", "09:00", null),
                SupportHours.makeNew("x", listOf("FRIDAY"), "09:00", null, null),
                SupportHours.makeNew("x", listOf("FRIDAY"), "9", "10:00", null),
                SupportHours.makeNew("x", listOf("FRIDAY"), "24:00", "25:00", null),
            ).forEachIndexed { index, it -> assertEquals(Result.failure(SupportHours.Failure.Invalid), it, "case $index") }
        }
    }

    @Nested
    inner class Social {
        @Test
        fun `a link with its channel, name and https page, a blank handle is none`() {
            val link = SocialLink.makeNew("TELEGRAM", "تلگرام", "https://t.me/kiva_shop", " ").getOrNull()!!
            assertEquals(SocialChannel.TELEGRAM, link.channel)
            assertNull(link.handle)
        }

        @Test
        fun `anything wrong with the link is SOCIAL_LINK_INVALID`() {
            listOf(
                SocialLink.makeNew("FACEBOOK", "x", "https://x.ir", null),
                SocialLink.makeNew("TELEGRAM", " ", "https://x.ir", null),
                SocialLink.makeNew("TELEGRAM", "x", "http://x.ir", null),
                SocialLink.makeNew("TELEGRAM", "x", "https://", null),
                SocialLink.makeNew("TELEGRAM", "x", "https://x.ir", "@" + "a".repeat(50)),
                SocialLink.makeNew("TELEGRAM", "ا".repeat(41), "https://x.ir", null),
            ).forEachIndexed { index, it -> assertEquals(Result.failure(SocialLink.Failure.Invalid), it, "case $index") }
        }
    }

    @Nested
    inner class Stats {
        @Test
        fun `the numbers with a satisfaction whose average is kept in tenths`() {
            val satisfaction = Satisfaction.makeNew("4.9", 2480, listOf(Satisfaction.MetricInput("PHOTO_MATCH", 98)), listOf("TELEGRAM", "INSTAGRAM")).getOrNull()!!
            val stats = SiteStats.makeNew(12_000, 48_000, 98, 60, Result.success(satisfaction)).getOrNull()!!

            assertEquals(49, stats.satisfaction?.averageRatingTenths)
            assertEquals(50, Satisfaction.makeNew("5", 0, emptyList(), emptyList()).getOrNull()?.averageRatingTenths)
            assertEquals(45, Satisfaction.makeNew("4.50", 0, emptyList(), emptyList()).getOrNull()?.averageRatingTenths)
        }

        @Test
        fun `numbers out of range under their own fields, every one at once`() {
            val failure = SiteStats.makeNew(-1, 10_000_001, 101, null, Result.success(null)).failureOrNull()

            assertEquals(
                listOf(
                    "happyCustomers NUMBER_OUT_OF_RANGE", "preShipmentPhotos NUMBER_OUT_OF_RANGE", "photoMatchSatisfactionPercent NUMBER_OUT_OF_RANGE",
                    "productModels FIELD_REQUIRED",
                ),
                pathsOf(failure),
            )
        }

        @Test
        fun `an average with two decimals or above 5 is RATING_INVALID, a metric twice or unknown, a source twice or unknown`() {
            val failure = Satisfaction.makeNew(
                "4.95", -1,
                listOf(Satisfaction.MetricInput("PHOTO_MATCH", 98), Satisfaction.MetricInput("PHOTO_MATCH", 90), Satisfaction.MetricInput("PRICE", 101)),
                listOf("TELEGRAM", "TELEGRAM", "FAX"),
            ).failureOrNull()

            assertEquals(
                listOf(
                    "averageRating RATING_INVALID", "totalCount NUMBER_OUT_OF_RANGE", "metrics.1.key LIST_ITEM_DUPLICATE", "metrics.2.key SATISFACTION_METRIC_INVALID",
                    "metrics.2.percent NUMBER_OUT_OF_RANGE", "sources.1 LIST_ITEM_DUPLICATE", "sources.2 SOCIAL_CHANNEL_INVALID",
                ),
                pathsOf(failure),
            )
            assertEquals(listOf("averageRating RATING_INVALID"), pathsOf(Satisfaction.makeNew("5.1", 0, emptyList(), emptyList()).failureOrNull()))
            assertEquals(listOf("averageRating RATING_INVALID"), pathsOf(Satisfaction.makeNew("خوب", 0, emptyList(), emptyList()).failureOrNull()))
        }

        @Test
        fun `stored numbers out of range are a bug`() {
            assertFailsWith<IllegalArgumentException> { SiteStats.constructFromPersisted(-1, 0, 0, 0, null) }
            assertFailsWith<IllegalArgumentException> { Satisfaction.constructFromPersisted(51, 0, emptyList(), emptyList()) }
        }
    }

    @Nested
    inner class Features {
        @Test
        fun `the three known switches, gift wrapping off`() {
            val features = ShopFeatures.makeNew(mapOf("wishlistShare" to true, "reviewMedia" to false, "giftWrap" to false)).getOrNull()!!

            assertEquals(mapOf("wishlistShare" to true, "reviewMedia" to false, "giftWrap" to false), features.byKey())
        }

        @Test
        fun `an unknown key, a known one left out, and gift wrapping on, all at once`() {
            val failure = ShopFeatures.makeNew(mapOf("darkMode" to true, "reviewMedia" to null, "giftWrap" to true)).failureOrNull()

            assertEquals(
                listOf("darkMode FEATURE_UNKNOWN", "wishlistShare FIELD_REQUIRED", "reviewMedia FIELD_REQUIRED", "giftWrap FEATURE_NOT_AVAILABLE"),
                pathsOf(failure),
            )
            assertFailsWith<IllegalArgumentException> { ShopFeatures.constructFromPersisted(wishlistShare = false, reviewMedia = false, giftWrap = true) }
        }
    }
}
