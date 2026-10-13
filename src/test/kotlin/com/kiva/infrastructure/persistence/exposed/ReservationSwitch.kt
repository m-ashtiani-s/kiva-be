package com.kiva.infrastructure.persistence.exposed

import com.kiva.infrastructure.persistence.exposed.tbl.ShopSettingsTbl
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * The shop's «رزرو ۴ روزه» switch on kiva_test, turned as the panel would turn it, for a test of the shop without it.
 * Always switched back: the other tests count on the migrations' value (on, since V19).
 */
object ReservationSwitch {

    suspend fun <T> with(db: Database, enabled: Boolean, block: suspend () -> T): T {
        val before = transaction(db) { ShopSettingsTbl.selectAll().single()[ShopSettingsTbl.reservationEnabled] }
        set(db, enabled)
        try {
            return block()
        } finally {
            set(db, before)
        }
    }

    private fun set(db: Database, enabled: Boolean) {
        transaction(db) { ShopSettingsTbl.update { it[reservationEnabled] = enabled } }
    }
}
