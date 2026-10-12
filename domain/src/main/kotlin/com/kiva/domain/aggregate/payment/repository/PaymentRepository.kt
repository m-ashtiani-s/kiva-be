package com.kiva.domain.aggregate.payment.repository

import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * Payment attempts, kept as rows. A change goes through a lock on the attempt's row ([lock], [lockByAuthority]) until
 * the transaction ends: a callback and the reconciliation job on the same attempt run one after the other, and each
 * sees what the other did. [save] checks the row's version too, the last wall.
 */
interface PaymentRepository {

    /** Stores a new attempt. */
    fun add(payment: Payment)

    /** Locks the attempt and reads it; null when there is none. */
    fun lock(publicId: PaymentPublicId): Payment?

    /**
     * Locks the attempt the gateway knows by [authority] and reads it: how a callback finds its payment, never by an id
     * in the callback's address. Null when this gateway opened no session with it for us.
     */
    fun lockByAuthority(gateway: PaymentGatewayCode, authority: GatewayAuthority): Payment?

    /** The attempt as it is, for reading only: nothing is locked. */
    fun find(publicId: PaymentPublicId): Payment?

    /**
     * Writes the attempt's changes, only if its row still has the version it was read with, and raises that version.
     * Throws ConcurrentChangeException when someone saved it in the meantime.
     */
    fun save(payment: Payment)

    /** Adds one exchange with the gateway to the attempt's log, for good. */
    fun appendLog(publicId: PaymentPublicId, entry: PaymentLogEntry)
}
