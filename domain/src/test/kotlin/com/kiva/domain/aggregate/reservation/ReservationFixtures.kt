package com.kiva.domain.aggregate.reservation

import com.kiva.domain.aggregate.address.model.valueobject.AddressId
import com.kiva.domain.aggregate.address.model.valueobject.AddressLine
import com.kiva.domain.aggregate.address.model.valueobject.RecipientName
import com.kiva.domain.aggregate.order.OrderFixtures
import com.kiva.domain.aggregate.order.OrderFixtures.now
import com.kiva.domain.aggregate.order.OrderFixtures.sara
import com.kiva.domain.aggregate.order.model.valueobject.AddressSnapshot
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.reservation.entity.ReservationGroup
import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupId
import com.kiva.domain.aggregate.reservation.repository.ReservationGroupIdGenerator
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import java.time.Instant

/** Sara's reservations: her golden order, paid at [now] to her address, holds group SG-1001 for 4 days. */
object ReservationFixtures {

    /** Hands out 1001, 1002, … as the sequence does. */
    class SequentialGroupIds : ReservationGroupIdGenerator {
        private var next = 1001L

        override fun nextReservationGroupId(): ReservationGroupId = ReservationGroupId.constructFromPersisted(next++)
    }

    /** Sara's address, as kept in the book ([addressId]) or not, with what an edit may change. */
    fun address(
        addressId: Long? = 42,
        line: String = "خیابان ولیعصر، بالاتر از پارک ساعی، کوچه نسترن",
        recipientName: String = "سارا محمدی",
    ): AddressSnapshot = OrderFixtures.address.let {
        AddressSnapshot.constructFromPersisted(
            addressId?.let(AddressId::constructFromPersisted), it.provinceName, it.cityName, AddressLine.constructFromPersisted(line), it.plaque,
            it.unit, it.postalCode, RecipientName.constructFromPersisted(recipientName), it.recipientPhone,
        )
    }

    fun group(
        address: AddressSnapshot = OrderFixtures.address,
        shippingMethod: ShippingMethodCode = ShippingMethodCode.POST,
        startedOn: Instant = now,
        id: Long = 1001,
    ): ReservationGroup = ReservationGroup.start(
        ReservationGroupId.constructFromPersisted(id), sara, OrderId.constructFromPersisted(1), address, shippingMethod, holdDays = 4, now = startedOn,
    )
}
