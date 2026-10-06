package com.kiva.infrastructure

import com.kiva.infrastructure.config.DatasourceConfig
import com.kiva.infrastructure.persistence.DatabaseFactory
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Connection to the separate `kiva_test` database used by integration tests.
 * Defaults match the local dev Postgres; override with KIVA_TEST_DB_URL / _USER / _PASSWORD.
 * Postgres must be running (here: D:\Programs\postgresql-17\start.cmd).
 *
 * On first use the database is wiped and every migration is applied from scratch, the same way the app does
 * it on startup. So each test run starts from exactly what the migrations create, seed data included,
 * and a broken migration fails the tests instead of the next deploy.
 * Tests that write must clean up after themselves (roll back, or delete what they added).
 */
object TestDatabase {
    val database: Database by lazy {
        val url = System.getenv("KIVA_TEST_DB_URL") ?: "jdbc:postgresql://localhost:5432/kiva_test"
        // clean() drops everything in the schema, so refuse anything that doesn't look like a test database.
        val databaseName = url.substringAfterLast('/').substringBefore('?')
        require(databaseName.endsWith("_test")) { "Tests wipe their database; '$databaseName' does not end with _test" }

        val dataSource = DatabaseFactory.createDataSource(
            DatasourceConfig(
                url = url,
                username = System.getenv("KIVA_TEST_DB_USER") ?: "kiva",
                password = System.getenv("KIVA_TEST_DB_PASSWORD") ?: "kiva",
                maxPoolSize = 4,
            ),
        )
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean()
        DatabaseFactory.migrate(dataSource)
        DatabaseFactory.connect(dataSource)
    }
}
