package com.kiva.infrastructure.persistence

import com.kiva.infrastructure.config.DatasourceConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
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

    /**
     * Exposed's own retry is off: by default it runs a whole transaction again, up to 3 times, after any SQL error. That
     * would run a use case again without it knowing (a race it answers itself, a bug that should stop at once). A use
     * case that wants another try asks for it, like AdjustInventoryUc.
     *
     * The SQL log shows the statements without their values ([SqlWithoutValuesLogger]).
     */
    fun connect(dataSource: DataSource): Database =
        Database.connect(
            dataSource,
            databaseConfig = DatabaseConfig {
                defaultMaxAttempts = 1
                sqlLogger = SqlWithoutValuesLogger
            },
        )
}
