package com.kiva.domain.aggregate.payment.repository

import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * Payment attempts, kept as rows. A change goes through a lock on the attempt's row until the transaction ends: on the
 * attempt alone ([lock]) to open its session, or on every attempt of its order together ([lockAttemptsOf]) to settle it,
 * so a callback, another attempt's callback and the jobs on one order run one after the other, and each sees what the
 * others did. [save] checks the row's version too, the last wall.
 */
interface PaymentRepository {

    /** Stores a new attempt. */
    fun add(payment: Payment)

    /** Locks the attempt and reads it; null when there is none. */
    fun lock(publicId: PaymentPublicId): Payment?

    /**
     * Locks every attempt of [orderId], always in the same order (oldest first), and reads them all. Settling an attempt
     * (a callback, the jobs) takes this lock, not the attempt's alone: whether an attempt may be confirmed depends on
     * the order's other attempts (PaymentDomainService), and one order of locking keeps two callbacks of one order from
     * waiting on each other.
     */
    fun lockAttemptsOf(orderId: OrderId): List<Payment>

    /**
     * The attempt the gateway knows by [authority], for reading only: how a callback finds its payment, never by an id
     * in the callback's address. Null when this gateway opened no session with it for us. To change it, lock its
     * order's attempts ([lockAttemptsOf]).
     */
    fun findByAuthority(gateway: PaymentGatewayCode, authority: GatewayAuthority): Payment?

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
