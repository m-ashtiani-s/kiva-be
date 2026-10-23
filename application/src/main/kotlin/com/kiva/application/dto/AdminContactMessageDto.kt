package com.kiva.application.dto

import com.kiva.domain.aggregate.contact.entity.ContactMessage

/**
 * A contact message as the panel sees it (`AdminContactMessage`): the whole message, and who wrote its last reply as the
 * panel shows a person, or null without a reply.
 */
data class AdminContactMessageDto(val message: ContactMessage, val repliedBy: ActorNameDto?)
