package com.kiva.application.dto

import com.kiva.domain.aggregate.ordernote.entity.OrderNote
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant

/**
 * A panel note on an order (the contract's AdminNote): what it says and who wrote it, by the name their account has now.
 * Personal data: [toString] shows the id only.
 */
data class AdminNoteDto(val id: Long, val text: String, val author: ActorNameDto, val createdOn: Instant) {
    override fun toString(): String = "AdminNoteDto(id=$id)"

    companion object {
        /** @param author the account of the note's author, or null when it is gone. */
        fun from(note: OrderNote, author: User?) = AdminNoteDto(
            id = checkNotNull(note.id) { "A note not stored has no id" },
            text = note.text.value,
            author = ActorNameDto.of(note.authorId, author),
            createdOn = note.createdOn,
        )
    }
}
