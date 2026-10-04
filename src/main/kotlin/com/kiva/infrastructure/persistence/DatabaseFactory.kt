package com.kiva.infrastructure.persistence

import com.kiva.infrastructure.config.DatasourceConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

object DatabaseFactory {

    fun createDataSource(config: DatasourceConfig): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.username
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
            },
        )

    /** Applies pending SQL files from resources/db/migration (V1__..., V2__...). Tables are never created from code. */
    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun connect(dataSource: DataSource): Database = Database.connect(dataSource)
}
