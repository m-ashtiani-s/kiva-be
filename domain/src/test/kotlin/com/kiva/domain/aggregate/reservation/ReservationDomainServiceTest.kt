package com.kiva.domain.aggregate.reservation

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.OrderFixtures.SequentialOrderIds
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.placement
import com.kiva.domain.aggregate.order.OrderDomainService
import com.kiva.domain.aggregate.order.PricingFixtures
import com.kiva.domain.aggregate.order.entity.Order
import com.kiva.domain.aggregate.order.entity.event.OrderReleasedFromReservation
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.PROCESSING
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus.RESERVED
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.reservation.ReservationFixtures.SequentialGroupIds
import com.kiva.domain.aggregate.reservation.ReservationFixtures.address
import com.kiva.domain.aggregate.reservation.ReservationFixtures.group
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus.HOLDING
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus.RELEASED
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode.POST
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReservationDomainServiceTest {

    private val service = ReservationDomainService(SequentialGroupIds())
    private val settings = PricingFixtures.shopSettings(reservationEnabled = true)
    private val orders = OrderDomainService(SequentialOrderIds())
    private val inTime: Instant = now.plus(Duration.ofDays(1))
    private val due: Instant = now.plus(Duration.ofDays(4))

    private fun order(reserve: Boolean = false, holding: ReservationGroup? = null, placedAt: Instant = now): Order =
        orders.placeOrder(placement(reserve = reserve, settings = settings).copy(holdingGroup = holding), placedAt).getOrNull()!!

    /** [order] paid at [at], as PaymentConfirmation does it: the reservation decided first, then the order marked paid. */
    private fun paid(order: Order, holding: ReservationGroup?, at: Instant): ReservationDomainService.OnPaid =
        service.onPaid(order, holding, settings, at).also {
            order.markPaid(PaymentPublicId.constructFromPersisted("pay_0000000000A1"), order.totals.payable, it.held, UserId.SYSTEM, at, it.groupId)
        }

    @Test
    fun `asked to reserve, with no group - the order starts one, held from its payment for 4 days`() {
        val order = order(reserve = true)
        val paidAt = now.plusSeconds(180)

        val onPaid = paid(order, holding = null, paidAt)

        val started = assertNotNull(onPaid.started)
        assertTrue(onPaid.held)
        assertEquals(started.id, onPaid.groupId)
        assertEquals("SG-1001", started.code.value)
        assertEquals(order.id, started.rootOrderId)
        assertEquals(paidAt.plus(Duration.ofDays(4)), started.expiresOn)
        assertEquals(POST, started.shippingMethod)
        assertEquals(RESERVED, order.status)
        assertEquals(started.id, order.reservationGroupId)
    }

    @Test
    fun `an order that joined a held group is RESERVED in it, and the group's time doesn't move`() {
        val group = group()
        val order = order(holding = group, placedAt = inTime)

        val onPaid = paid(order, group, inTime.plusSeconds(60))

        assertEquals(ReservationDomainService.OnPaid(held = true, groupId = group.id, started = null), onPaid)
        assertEquals(RESERVED, order.status)
        assertEquals(now.plus(Duration.ofDays(4)), group.expiresOn)
    }

    @Test
    fun `an order that joined a group whose time ran out before it was paid - PROCESSING, shipping with it and free all the same`() {
        val group = group()
        val order = order(holding = group, placedAt = due.minusSeconds(60))

        val onPaid = paid(order, group, due.plusSeconds(60))

        assertEquals(ReservationDomainService.OnPaid(held = false, groupId = group.id, started = null), onPaid)
        assertEquals(PROCESSING, order.status)
        assertEquals(group.id, order.reservationGroupId)
        assertTrue(order.totals.shippingIsFree)
    }

    @Test
    fun `asked to reserve while a group started meanwhile - at its address it joins it, elsewhere it ships now`() {
        val sameAddress = order(reserve = true)
        val elsewhere = orders.placeOrder(placement(reserve = true, settings = settings).copy(address = address(addressId = 43)), now).getOrNull()!!
        val startedMeanwhile = group(startedOn = now.plusSeconds(60))

        assertEquals(ReservationDomainService.OnPaid(true, startedMeanwhile.id, null), paid(sameAddress, startedMeanwhile, now.plusSeconds(120)))
        assertEquals(ReservationDomainService.OnPaid(false, null, null), paid(elsewhere, startedMeanwhile, now.plusSeconds(120)))
        assertEquals(listOf(RESERVED, PROCESSING), listOf(sameAddress.status, elsewhere.status))
    }

    @Test
    fun `asked to reserve while the customer's group is over but not yet let go - it ships now, nothing starts`() {
        val over = group(startedOn = now.minus(Duration.ofDays(5)))
        val order = order(reserve = true, holding = over)

        assertEquals(ReservationDomainService.OnPaid(false, null, null), paid(order, over, now.plusSeconds(60)))
        assertEquals(PROCESSING, order.status)
    }

    @Test
    fun `an order that neither joined nor asked is PROCESSING, whatever the customer holds`() {
        val group = group()
        val elsewhere = orders.placeOrder(placement(settings = settings).copy(address = address(addressId = 43), holdingGroup = group), inTime).getOrNull()!!

        assertEquals(ReservationDomainService.OnPaid(false, null, null), paid(elsewhere, group, inTime))
        assertEquals(PROCESSING, elsewhere.status)
    }

    @Test
    fun `the group an order joins - the held one, at its address - none without an address in the book`() {
        val group = group()

        assertSame(group, service.groupTaking(group, address(), inTime))
        assertNull(service.groupTaking(group, address(addressId = 43), inTime))
        assertNull(service.groupTaking(group, null, inTime))
        assertNull(service.groupTaking(null, address(), inTime))
        assertNull(service.groupTaking(group, address(), due))
    }

    @Test
    fun `letting go - not before its time - then every held order is PROCESSING and the group RELEASED, once`() {
        val first = order(reserve = true)
        val group = assertNotNull(paid(first, null, now).started)
        val second = order(holding = group, placedAt = inTime).also { paid(it, group, inTime) }

        assertFalse(service.release(group, listOf(first, second), due.minusSeconds(1)))
        assertEquals(listOf(RESERVED, RESERVED), listOf(first.status, second.status))
        assertEquals(HOLDING, group.status)

        assertTrue(service.release(group, listOf(first, second), due))
        assertEquals(listOf(PROCESSING, PROCESSING), listOf(first.status, second.status))
        assertIs<OrderReleasedFromReservation>(second.getRecordedEvents().last())
        assertEquals(RELEASED, group.status)

        val eventsBefore = first.getRecordedEvents().size
        assertFalse(service.release(group, listOf(first), due.plusSeconds(60)))
        assertEquals(eventsBefore, first.getRecordedEvents().size)
    }

    @Test
    fun `an order of another group can't be let go with this one`() {
        val other = group(id = 1002)
        val stranger = order(holding = other, placedAt = inTime).also { paid(it, other, inTime) }

        assertFailsWith<IllegalArgumentException> { service.release(group(), listOf(stranger), due) }
    }
}
