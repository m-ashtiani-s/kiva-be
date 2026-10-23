package com.kiva.infrastructure.httpserver.controller.httprest.model.response

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

/** Contract schema `ContactMessageResponse`. */
@Serializable
data class ContactMessageHttpResponse(val ticketCode: String, val createdAt: String, val message: String) {
    companion object {
        fun from(created: CreateContactMessageUc.Created) =
            ContactMessageHttpResponse(created.ticketCode.value, created.createdAt.toString(), ContactTexts.received(created.ticketCode))
    }
}
