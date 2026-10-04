package com.kiva.infrastructure

import com.kiva.infrastructure.config.DatasourceConfig
import com.kiva.infrastructure.persistence.DatabaseFactory
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Connection to the separate `kiva_test` database used by integration tests.
 * Defaults match the local dev Postgres; override with KIVA_TEST_DB_URL / _USER / _PASSWORD.
 * Postgres must be running (D:\Personal\kiva\pg-start.cmd).
 */
object TestDatabase {
    val database: Database by lazy {
        DatabaseFactory.connect(
            DatabaseFactory.createDataSource(
                DatasourceConfig(
                    url = System.getenv("KIVA_TEST_DB_URL") ?: "jdbc:postgresql://localhost:5432/kiva_test",
                    username = System.getenv("KIVA_TEST_DB_USER") ?: "kiva",
                    password = System.getenv("KIVA_TEST_DB_PASSWORD") ?: "kiva",
                    maxPoolSize = 4,
                ),
            ),
        )
    }
}
