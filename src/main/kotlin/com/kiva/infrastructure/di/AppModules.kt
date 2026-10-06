package com.kiva.infrastructure.di

import com.kiva.infrastructure.config.AppConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.core.module.Module

/** Every module the app loads, in one list, so a test can build exactly the same graph. */
fun appModules(config: AppConfig, database: Database): List<Module> =
    listOf(infrastructureModule(config, database), daoModule, domainServiceModule, useCaseModule, seedModule)
