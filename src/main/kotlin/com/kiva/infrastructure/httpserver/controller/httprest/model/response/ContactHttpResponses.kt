package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.AdminContactMessageDto
import com.kiva.application.usecase.contact.usecase.command.CreateContactMessageUc
import com.kiva.domain.aggregate.contact.model.valueobject.ContactTopic
import com.kiva.domain.aggregate.contact.model.valueobject.TicketCode
import kotlinx.serialization.Serializable

/** The contact form's Persian words (roadmap phase 13, decisions 1 and 5). */
object ContactTexts {
    fun topicLabel(topic: ContactTopic): String = when (topic) {
        ContactTopic.ORDER_FOLLOWUP -> "پیگیری سفارش"
        ContactTopic.PRODUCT_QUESTION -> "سؤال درباره‌ی محصول"
        ContactTopic.RETURN -> "مرجوعی"
        ContactTopic.COLLABORATION -> "همکاری"
        ContactTopic.OTHER -> "سایر"
    }

    fun received(code: TicketCode): String = "پیامت رسید! کد پیگیریت ${code.value}ـه؛ به زودی باهات تماس می‌گیریم."
}

/** Contract schema `ContactTopicOption`. */
@Serializable
data class ContactTopicOptionHttpResponse(val value: String, val label: String, val requiresOrderCode: Boolean) {
    companion object {
        fun from(topic: ContactTopic) = ContactTopicOptionHttpResponse(topic.name, ContactTexts.topicLabel(topic), topic.showsOrderCode)
    }
}

/** Contract schema `AdminContactMessage`: every field always there; [repliedBy] the operator as the panel shows a person. */
@Serializable
data class AdminContactMessageHttpResponse(
    val ticketCode: String,
    val topic: String,
    val fullName: String,
    val phone: String,
    val email: String?,
    val orderCode: String?,
    val message: String,
    val status: String,
    val reply: String?,
    val replyChannel: String?,
    val repliedAt: String?,
    val repliedBy: String?,
    val internalNote: String?,
    val userId: Long?,
    val createdAt: String,
    val updatedAt: String,
) {
    override fun toString(): String = "AdminContactMessageHttpResponse($ticketCode, $status)"

    companion object {
        fun from(dto: AdminContactMessageDto): AdminContactMessageHttpResponse {
            val m = dto.message
            return AdminContactMessageHttpResponse(
                ticketCode = m.ticketCode.value,
                topic = m.topic.name,
                fullName = m.fullName,
                phone = m.phone.value,
                email = m.email?.value,
                orderCode = m.orderCode?.value,
                message = m.message,
                status = m.status.name,
                reply = m.reply,
                replyChannel = m.replyChannel?.name,
                repliedAt = m.repliedAt?.toString(),
                repliedBy = dto.repliedBy?.let { PanelTexts.actorName(it) },
                internalNote = m.internalNote,
                userId = m.userId?.value,
                createdAt = m.createdAt.toString(),
                updatedAt = m.updatedAt.toString(),
            )
        }
    }
}

/** Contract schema `ContactMessageResponse`. */
@Serializable
data class ContactMessageHttpResponse(val ticketCode: String, val createdAt: String, val message: String) {
    companion object {
        fun from(created: CreateContactMessageUc.Created) =
            ContactMessageHttpResponse(created.ticketCode.value, created.createdAt.toString(), ContactTexts.received(created.ticketCode))
    }
}
