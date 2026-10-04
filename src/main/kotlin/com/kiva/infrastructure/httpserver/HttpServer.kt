package com.kiva.infrastructure.httpserver

import com.kiva.infrastructure.httpserver.route.healthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.slf4j.event.Level

fun Application.configureHttp() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(CallLogging) {
        level = Level.INFO
    }
}

fun Application.configureRouting() {
    routing {
        healthRoutes()
    }
}
