package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.contact.entity.ContactMessage
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import com.kiva.domain.aggregate.contact.repository.ContactMessageRepository
import com.kiva.domain.aggregate.order.model.valueobject.OrderCode
import com.kiva.domain.aggregate.user.model.valueobject.Email
import com.kiva.infrastructure.persistence.exposed.tbl.ContactMessageTbl
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.infrastructure.common.persistence.SortOrders
import com.kiva.infrastructure.common.persistence.paginate
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class ContactMessageRepositoryImpl : ContactMessageRepository {

    // `INSERT ... ON CONFLICT DO NOTHING`: a taken code inserts nothing and leaves the transaction usable for the next try.
    override fun addIfCodeFree(message: ContactMessage): Boolean =
        ContactMessageTbl.insertIgnore {
            it[ticketCode] = message.ticketCode.value
            it[createdAt] = message.createdAt.utc()
            it.fill(message)
        }.insertedCount == 1

    override fun find(ticketCode: TicketCode): ContactMessage? =
        ContactMessageTbl.selectAll().where { ContactMessageTbl.ticketCode eq ticketCode.value }.singleOrNull()?.toMessage()

    override fun lockFind(ticketCode: TicketCode): ContactMessage? =
        ContactMessageTbl.selectAll().where { ContactMessageTbl.ticketCode eq ticketCode.value }.forUpdate().singleOrNull()?.toMessage()

    override fun save(message: ContactMessage) {
        ContactMessageTbl.update({ ContactMessageTbl.ticketCode eq message.ticketCode.value }) { it.fill(message) }
    }

    override fun page(filter: ContactMessageRepository.Filter, pageable: Pageable): Page<ContactMessage> {
        val conditions = listOfNotNull(
            filter.status?.let { ContactMessageTbl.status eq it },
            filter.topic?.let { ContactMessageTbl.topic eq it },
        )
        val where: Op<Boolean> = conditions.fold(Op.TRUE as Op<Boolean>) { all, one -> all and one }
        return ContactMessageTbl.selectAll().where { where }
            .paginate(pageable, NEWEST_FIRST, tiebreaker = ContactMessageTbl.id, tiebreakerOrder = SortOrder.DESC) { it.toMessage() }
    }

    override fun deleteClosedBefore(before: Instant): Int =
        ContactMessageTbl.deleteWhere { (ContactMessageTbl.status eq ContactStatus.CLOSED) and (ContactMessageTbl.updatedAt less before.utc()) }

    // A transaction-level advisory lock keyed by the mobile, a parameter so it never shows in the SQL log.
    override fun lockPhone(phone: IranianMobileNumber) {
        TransactionManager.current().exec("SELECT pg_advisory_xact_lock(hashtext(?))", listOf(TextColumnType() to "contact:${phone.value}")) { }
    }

    override fun createdSince(phone: IranianMobileNumber, since: Instant): List<Instant> =
        ContactMessageTbl.select(ContactMessageTbl.createdAt)
            .where { (ContactMessageTbl.phone eq phone.value) and (ContactMessageTbl.createdAt greater since.utc()) }
            .map { it[ContactMessageTbl.createdAt].toInstant() }

    private fun UpdateBuilder<*>.fill(message: ContactMessage) {
        this[ContactMessageTbl.topic] = message.topic
        this[ContactMessageTbl.fullName] = message.fullName
        this[ContactMessageTbl.phone] = message.phone.value
        this[ContactMessageTbl.email] = message.email?.value
        this[ContactMessageTbl.orderCode] = message.orderCode?.value
        this[ContactMessageTbl.message] = message.message
        this[ContactMessageTbl.status] = message.status
        this[ContactMessageTbl.reply] = message.reply
        this[ContactMessageTbl.replyChannel] = message.replyChannel
        this[ContactMessageTbl.repliedAt] = message.repliedAt?.utc()
        this[ContactMessageTbl.repliedBy] = message.repliedBy?.value
        this[ContactMessageTbl.internalNote] = message.internalNote
        this[ContactMessageTbl.userId] = message.userId?.value
        this[ContactMessageTbl.updatedAt] = message.updatedAt.utc()
    }

    private fun ResultRow.toMessage(): ContactMessage = ContactMessage.constructFromPersisted(
        ticketCode = TicketCode.constructFromPersisted(this[ContactMessageTbl.ticketCode]),
        topic = this[ContactMessageTbl.topic],
        fullName = this[ContactMessageTbl.fullName],
        phone = IranianMobileNumber.constructFromPersisted(this[ContactMessageTbl.phone]),
        email = this[ContactMessageTbl.email]?.let { Email.constructFromPersisted(it) },
        orderCode = this[ContactMessageTbl.orderCode]?.let { OrderCode.constructFromPersisted(it) },
        message = this[ContactMessageTbl.message],
        status = this[ContactMessageTbl.status],
        reply = this[ContactMessageTbl.reply],
        replyChannel = this[ContactMessageTbl.replyChannel],
        repliedAt = this[ContactMessageTbl.repliedAt]?.toInstant(),
        repliedBy = this[ContactMessageTbl.repliedBy]?.let { UserId.constructFromPersisted(it) },
        internalNote = this[ContactMessageTbl.internalNote],
        userId = this[ContactMessageTbl.userId]?.let { UserId.constructFromPersisted(it) },
        createdAt = this[ContactMessageTbl.createdAt].toInstant(),
        updatedAt = this[ContactMessageTbl.updatedAt].toInstant(),
    )

    private fun Instant.utc() = atOffset(ZoneOffset.UTC)

    private companion object {
        val NEWEST_FIRST: SortOrders = mapOf("newest" to listOf(ContactMessageTbl.createdAt to SortOrder.DESC))
    }
}
