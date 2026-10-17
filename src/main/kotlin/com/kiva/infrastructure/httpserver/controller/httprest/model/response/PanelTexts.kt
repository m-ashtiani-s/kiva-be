package com.kiva.infrastructure.httpserver.controller.httprest.model.response

import com.kiva.application.dto.ActorNameDto

/** Persian texts the panel's answers share. */
object PanelTexts {

    /** Who did it, as the panel shows it: the full name, otherwise the phone masked; «سیستم» for the system's own work. */
    fun actorName(actor: ActorNameDto): String = when (actor) {
        ActorNameDto.System -> "سیستم"
        ActorNameDto.Unknown -> "حساب حذف‌شده"
        is ActorNameDto.Person -> actor.fullName ?: actor.maskedPhone
    }
}
