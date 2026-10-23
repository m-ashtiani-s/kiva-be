package com.kiva.domain.aggregate.contact.repository

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.domain.aggregate.contact.entity.ContactMessage
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.domain.aggregate.contact.model.valueobject.ContactStatus
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import java.time.Instant

/** Contact messages, by their ticket code. Runs inside the use case's transaction. */
interface ContactMessageRepository {
    /** Stores a new message; false (and nothing stored) when its ticket code is already taken. */
    fun addIfCodeFree(message: ContactMessage): Boolean

    fun find(ticketCode: TicketCode): ContactMessage?

    /** The message, its row held until the transaction ends (the panel's change). */
    fun lockFind(ticketCode: TicketCode): ContactMessage?

    /** Writes back a message read with [lockFind]. */
    fun save(message: ContactMessage)

    /** The panel's list (decision 18): newest first, the newest id first among equals. */
    fun page(filter: Filter, pageable: Pageable): Page<ContactMessage>

    /** Deletes the CLOSED messages last changed before [before] (decision 19); how many went. */
    fun deleteClosedBefore(before: Instant): Int

    /** The panel's filters; null is any. */
    data class Filter(val status: ContactStatus?, val topic: ContactTopic?)

    /**
     * Holds this mobile's messages until the transaction ends, so two sent at once are counted one after the other and
     * the limit per mobile can't be passed by racing it.
     */
    fun lockPhone(phone: IranianMobileNumber)

    /** When this mobile's messages were sent after [since] (the limit per mobile, decision 3). */
    fun createdSince(phone: IranianMobileNumber, since: Instant): List<Instant>
}
