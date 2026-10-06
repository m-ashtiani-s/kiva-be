package com.kiva.infrastructure.di

import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.DatasourceConfig
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertNotNull

class AppModulesTest {

    // Only the database connection is used; the datasource settings are read at boot, before Koin.
    private val config = AppConfig(DatasourceConfig("jdbc:postgresql://localhost:5432/kiva_test", "unused", "unused", 1))

    /** Everything is created at start, so a missing binding fails here instead of when the server boots. */
    @Test
    fun `the app's modules build the whole graph, domain services and use cases included`() {
        val app = koinApplication { modules(appModules(config, TestDatabase.database)) }
        try {
            assertNotNull(app.koin.get<ProductDomainService>())
            assertNotNull(app.koin.get<InventoryDomainService>())
            assertNotNull(app.koin.get<AdjustInventoryUc>())
        } finally {
            app.close()
        }
    }
}
