package com.kiva.infrastructure

import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.di.appModules
import com.kiva.infrastructure.httpserver.auth.configureAuthentication
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.configureRouting
import com.kiva.infrastructure.persistence.DatabaseFactory
import com.kiva.infrastructure.seed.DevCatalogSeeder
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

/** Run this to start the server (the ▶ next to it in IntelliJ). Port and settings come from application.conf. */
fun main(args: Array<String>): Unit =
    io.ktor.server.cio.EngineMain.main(args)

/**
 * Called by Ktor after [main] has read application.conf (ktor.application.modules points here).
 * Boot order, top to bottom:
 *   config -> datasource -> migrations -> Exposed -> DI -> dev seed (when on) -> HTTP plugins -> authentication -> routes
 */
@Suppress("unused") // referenced only from application.conf, so the IDE can't see it is used
fun Application.start() {
    val config = AppConfig.from(environment.config)

    val dataSource = DatabaseFactory.createDataSource(config.datasource)
    DatabaseFactory.migrate(dataSource)
    val database = DatabaseFactory.connect(dataSource)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }

    install(Koin) {
        slf4jLogger()
        modules(appModules(config, database))
    }

    // Before the routes, so the first request already sees the seeded catalog.
    if (config.seed.enabled) runBlocking { get<DevCatalogSeeder>().seed() }

    configureHttp(config.http.allowedOrigins, config.http.trustedProxyHops)
    configureAuthentication(get<JwtAccessTokens>())
    configureRouting()
}
