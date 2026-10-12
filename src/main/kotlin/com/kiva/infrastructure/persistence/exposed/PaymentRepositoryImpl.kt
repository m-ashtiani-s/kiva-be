package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.event.ConcurrentChangeException
import com.kiva.common.event.valueobject.EventAggregateId
import com.kiva.common.event.valueobject.EventAggregateName
import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import com.kiva.domain.aggregate.payment.entity.Payment
import com.kiva.domain.aggregate.payment.model.PaymentLogEntry
import com.kiva.domain.aggregate.payment.model.valueobject.CardMask
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayAuthority
import com.kiva.domain.aggregate.payment.model.valueobject.GatewayRefId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentPublicId
import com.kiva.domain.aggregate.payment.model.valueobject.PaymentRedirect
import com.kiva.domain.aggregate.payment.repository.PaymentRepository
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.PaymentTbl
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class PaymentRepositoryImpl : PaymentRepository {

    override fun add(payment: Payment) {
        PaymentTbl.insert {
            it[publicId] = payment.publicId.value
            it[orderId] = payment.orderId.value
            it[userId] = payment.userId.value
            it[gateway] = payment.gateway
            it[amount] = payment.amount.toman
            it[createdOn] = payment.createdOn.utc()
            it[version] = payment.version
            it.writeChanges(payment)
        }
    }

    override fun lock(publicId: PaymentPublicId): Payment? = lockAndRead(PaymentTbl.publicId eq publicId.value)

    override fun lockByAuthority(gateway: PaymentGatewayCode, authority: GatewayAuthority): Payment? =
        lockAndRead((PaymentTbl.gateway eq gateway) and (PaymentTbl.authority eq authority.value))

    override fun find(publicId: PaymentPublicId): Payment? =
        PaymentTbl.selectAll().where { PaymentTbl.publicId eq publicId.value }.singleOrNull()?.toPayment()

    /** Only if the row still has the version [payment] was read with; the row's lock makes this the second wall. */
    override fun save(payment: Payment) {
        val updated = PaymentTbl.update({ (PaymentTbl.publicId eq payment.publicId.value) and (PaymentTbl.version eq payment.version) }) {
            it[version] = payment.version + 1
            it.writeChanges(payment)
        }
        if (updated != 1) throw ConcurrentChangeException(AGGREGATE_NAME, EventAggregateId(payment.publicId.value))
    }

    override fun appendLog(publicId: PaymentPublicId, entry: PaymentLogEntry) {
        val paymentId = PaymentTbl.select(PaymentTbl.id).where { PaymentTbl.publicId eq publicId.value }.singleOrNull()?.get(PaymentTbl.id)?.value
            ?: error("A log entry for payment ${publicId.value}, which is not stored")
        PaymentLogTbl.insert {
            it[PaymentLogTbl.paymentId] = paymentId
            it[occurredOn] = entry.occurredOn.utc()
            it[kind] = entry.kind
            it[outcome] = entry.outcome
            it[gatewayCode] = entry.gatewayCode
            it[httpStatus] = entry.httpStatus
            it[durationMs] = entry.duration?.toMillis()?.let(Math::toIntExact)
        }
    }

    private fun lockAndRead(which: Op<Boolean>): Payment? = PaymentTbl.selectAll().where { which }.forUpdate().singleOrNull()?.toPayment()

    /** Every column a change can touch; the attempt's id, order, owner, gateway, amount and creation never change. */
    private fun UpdateBuilder<*>.writeChanges(payment: Payment) {
        this[PaymentTbl.status] = payment.status
        this[PaymentTbl.authority] = payment.authority?.value
        this[PaymentTbl.redirectUrl] = payment.redirect?.url
        this[PaymentTbl.redirectMethod] = payment.redirect?.method
        this[PaymentTbl.redirectFields] = payment.redirect?.fields
        this[PaymentTbl.refId] = payment.refId?.value
        this[PaymentTbl.cardMask] = payment.cardMask?.value
        this[PaymentTbl.failureReason] = payment.failureReason
        this[PaymentTbl.redirectedOn] = payment.redirectedOn?.utc()
        this[PaymentTbl.verifyingSince] = payment.verifyingSince?.utc()
        this[PaymentTbl.settledOn] = payment.settledOn?.utc()
    }

    private fun ResultRow.toPayment(): Payment = Payment.constructFromPersisted(
        publicId = PaymentPublicId.constructFromPersisted(this[PaymentTbl.publicId]),
        orderId = OrderId.constructFromPersisted(this[PaymentTbl.orderId]),
        userId = UserId.constructFromPersisted(this[PaymentTbl.userId]),
        gateway = this[PaymentTbl.gateway],
        amount = Money.constructFromPersisted(this[PaymentTbl.amount]),
        status = this[PaymentTbl.status],
        authority = this[PaymentTbl.authority]?.let { GatewayAuthority.constructFromPersisted(it) },
        redirect = this[PaymentTbl.redirectUrl]?.let { url ->
            PaymentRedirect(url, this[PaymentTbl.redirectMethod] ?: error("A redirect without its method"), this[PaymentTbl.redirectFields].orEmpty())
        },
        refId = this[PaymentTbl.refId]?.let { GatewayRefId.constructFromPersisted(it) },
        cardMask = this[PaymentTbl.cardMask]?.let { CardMask.constructFromPersisted(it) },
        failureReason = this[PaymentTbl.failureReason],
        createdOn = this[PaymentTbl.createdOn].toInstant(),
        redirectedOn = this[PaymentTbl.redirectedOn]?.toInstant(),
        verifyingSince = this[PaymentTbl.verifyingSince]?.toInstant(),
        settledOn = this[PaymentTbl.settledOn]?.toInstant(),
        version = this[PaymentTbl.version],
    )

    private fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        val AGGREGATE_NAME = EventAggregateName("PAYMENT")
    }
}
