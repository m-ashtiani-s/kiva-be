package com.kiva.infrastructure.httpserver.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.koin.ktor.ext.inject

@Serializable
data class HealthResponse(val status: String, val database: String)

fun Route.healthRoutes() {
    val database by inject<Database>()

    get("/health") {
        // JDBC blocks the thread, so it runs on the IO dispatcher, not on Ktor's request threads.
        val dbUp = withContext(Dispatchers.IO) {
            runCatching { transaction(database) { exec("SELECT 1") } }.isSuccess
        }
        if (dbUp) {
            call.respond(HttpStatusCode.OK, HealthResponse(status = "UP", database = "UP"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "DOWN", database = "DOWN"))
        }
    }
}
