package com.kiva.infrastructure.persistence.exposed.tbl

import com.kiva.domain.aggregate.reservation.model.valueobject.ReservationGroupStatus
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

/** Reservation groups («رزرو ۴ روزه»). Created by V19__reservation_groups.sql (Exposed never creates tables); keep both in step. */
object ReservationGroupTbl : LongIdTable(name = "reservationgroup_tbl", columnName = "pId") {
    val groupId = long("groupId").uniqueIndex()
    val code = varchar("code", 24).uniqueIndex()
    val userId = long("userId").references(UserTbl.id)
    val rootOrderId = long("rootOrderId")
    val addressId = long("addressId").nullable()
    val addressFingerprint = binary("addressFingerprint", 32)
    val shippingMethod = enumerationByName<ShippingMethodCode>("shippingMethod", 10)
    val startedOn = timestampWithTimeZone("startedOn")
    val expiresOn = timestampWithTimeZone("expiresOn")
    val status = enumerationByName<ReservationGroupStatus>("status", 10)
    val releasedOn = timestampWithTimeZone("releasedOn").nullable()
    val version = long("version").default(0)
}
