package com.kiva.domain.aggregate.order.model.policy

import com.kiva.domain.aggregate.order.PricingFixtures.date
import com.kiva.domain.aggregate.order.PricingFixtures.days
import com.kiva.domain.aggregate.order.model.valueobject.DeliveryWindow
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.FRIDAY
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeliveryWindowPolicyTest {

    private val fridays = setOf(FRIDAY)
    private val thursday = date("2026-10-15")
    private val friday = date("2026-10-16")

    @Nested
    inner class ShipDate {
        @Test
        fun `an order ships the day it is placed`() {
            assertEquals(thursday, DeliveryWindowPolicy.shipDate(thursday, holdDays = null, fridays))
        }

        @Test
        fun `an order placed on Friday ships on Saturday`() {
            assertEquals(date("2026-10-17"), DeliveryWindowPolicy.shipDate(friday, holdDays = null, fridays))
        }

        @Test
        fun `a reserved order ships after the hold - 2026-10-11 plus 4 days is 2026-10-15`() {
            assertEquals(date("2026-10-15"), DeliveryWindowPolicy.shipAfterDate(date("2026-10-11"), 4))
            assertEquals(date("2026-10-15"), DeliveryWindowPolicy.shipDate(date("2026-10-11"), holdDays = 4, fridays))
        }

        @Test
        fun `a hold ending on Friday ships on Saturday, but the promise stays «after Friday»`() {
            assertEquals(friday, DeliveryWindowPolicy.shipAfterDate(date("2026-10-12"), 4))
            assertEquals(date("2026-10-17"), DeliveryWindowPolicy.shipDate(date("2026-10-12"), holdDays = 4, fridays))
        }

        @Test
        fun `negative hold days are a bug`() {
            assertFailsWith<IllegalArgumentException> { DeliveryWindowPolicy.shipAfterDate(thursday, -1) }
        }
    }

    @Nested
    inner class Window {
        @Test
        fun `the roadmap's example - post on Thursday 2026-10-15 arrives 2026-10-19 to 2026-10-21`() {
            assertEquals(
                DeliveryWindow(date("2026-10-19"), date("2026-10-21")),
                DeliveryWindowPolicy.windowOf(thursday, days(3, 5), fridays),
            )
        }

        @Test
        fun `and tipax from the same day arrives 2026-10-17 to 2026-10-18`() {
            assertEquals(
                DeliveryWindow(date("2026-10-17"), date("2026-10-18")),
                DeliveryWindowPolicy.windowOf(thursday, days(1, 2), fridays),
            )
        }

        @Test
        fun `shipped on Saturday after a Friday order, post arrives a day later than Thursday's - 2026-10-20 to 2026-10-22`() {
            val shipDate = DeliveryWindowPolicy.shipDate(friday, holdDays = null, fridays)

            assertEquals(DeliveryWindow(date("2026-10-20"), date("2026-10-22")), DeliveryWindowPolicy.windowOf(shipDate, days(3, 5), fridays))
        }

        @Test
        fun `a method that takes 0 days starts on the ship date itself`() {
            assertEquals(DeliveryWindow(thursday, date("2026-10-17")), DeliveryWindowPolicy.windowOf(thursday, days(0, 1), fridays))
        }
    }
}
