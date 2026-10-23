package com.kiva.domain.aggregate.contact.model.valueobject

import com.kiva.common.text.normalizeDigits

/**
 * A contact message's code (`CT-482113`): what the customer is told and quotes to support, and the panel's path. Six
 * random digits (roadmap phase 13, decision 4): a running number would tell how many messages the shop gets.
 */
@JvmInline
value class TicketCode private constructor(val value: String) {

    // Second wall: the generator and [of] only make good ones, so this fails only on bad stored data.
    init {
        require(FORMAT.matches(value)) { "Invalid TicketCode '$value'" }
    }

    companion object {
        private val FORMAT = Regex("CT-[0-9]{6}")

        /** The code from its six digits, 0 to 999999. */
        fun ofNumber(number: Int): TicketCode {
            require(number in 0..999_999) { "A ticket number has six digits, not $number" }
            return TicketCode("CT-" + number.toString().padStart(6, '0'))
        }

        /** A code typed or in a path: trimmed, upper case, Persian digits made Latin (`ct-۴۸۲۱۱۳`); null when it can't be one. */
        fun of(raw: String): TicketCode? = raw.trim().uppercase().normalizeDigits().takeIf { FORMAT.matches(it) }?.let { TicketCode(it) }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: String): TicketCode = TicketCode(value)
    }
}

/**
 * What a contact message is about (the contract's ContactTopic, decision 1). [showsOrderCode] is the contract's
 * `requiresOrderCode`: the form shows its optional «شماره سفارش» field, never makes it required.
 */
enum class ContactTopic(val showsOrderCode: Boolean) {
    ORDER_FOLLOWUP(true),
    PRODUCT_QUESTION(false),
    RETURN(true),
    COLLABORATION(false),
    OTHER(false),
    ;

    companion object {
        /** By its exact name; null for anything else. */
        fun of(raw: String): ContactTopic? = entries.firstOrNull { it.name == raw.trim() }
    }
}

/** Where support is with a message (decision 9: any to any, no state machine). */
enum class ContactStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED,
    ;

    companion object {
        fun of(raw: String): ContactStatus? = entries.firstOrNull { it.name == raw.trim() }
    }
}

/** How a reply goes: SMS only says there is one (decision 7), EMAIL carries its text (decision 8). */
enum class ReplyChannel {
    SMS,
    EMAIL,
    ;

    companion object {
        fun of(raw: String): ReplyChannel? = entries.firstOrNull { it.name == raw.trim() }
    }
}
