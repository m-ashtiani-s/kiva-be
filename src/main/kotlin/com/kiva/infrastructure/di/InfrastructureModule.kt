package com.kiva.infrastructure.di

import com.kiva.infrastructure.config.AppConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module

/** Things built during boot that other components may need. Repositories and use cases get their own modules later. */
fun infrastructureModule(config: AppConfig, database: Database) = module {
    single { config }
    single { database }
}
