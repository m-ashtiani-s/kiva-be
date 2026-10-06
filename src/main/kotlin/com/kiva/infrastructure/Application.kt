package com.kiva.infrastructure

import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.di.daoModule
import com.kiva.infrastructure.di.infrastructureModule
import com.kiva.infrastructure.di.useCaseModule
import com.kiva.infrastructure.httpserver.configureHttp
import com.kiva.infrastructure.httpserver.configureRouting
import com.kiva.infrastructure.persistence.DatabaseFactory
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

/**
 * Entry point referenced from application.conf (ktor.application.modules).
 * Boot order, top to bottom:
 *   config -> datasource -> migrations -> Exposed -> DI -> HTTP plugins -> routes
 */
fun Application.module() {
    val config = AppConfig.from(environment.config)

    val dataSource = DatabaseFactory.createDataSource(config.datasource)
    DatabaseFactory.migrate(dataSource)
    val database = DatabaseFactory.connect(dataSource)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }

    install(Koin) {
        slf4jLogger()
        modules(infrastructureModule(config, database), daoModule, useCaseModule)
    }

    configureHttp()
    configureRouting()
}
