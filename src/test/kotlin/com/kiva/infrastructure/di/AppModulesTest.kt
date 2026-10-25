package com.kiva.infrastructure.di

import com.kiva.application.port.SmsSender
import com.kiva.application.usecase.account.usecase.query.GetMeUc
import com.kiva.application.usecase.auth.usecase.command.SendLoginCodeUc
import com.kiva.application.usecase.auth.usecase.command.VerifyLoginCodeUc
import com.kiva.application.usecase.inventory.usecase.command.AdjustInventoryUc
import com.kiva.domain.aggregate.inventory.InventoryDomainService
import com.kiva.domain.aggregate.product.ProductDomainService
import com.kiva.infrastructure.TestDatabase
import com.kiva.infrastructure.auth.sms.FarazSmsConfig
import com.kiva.infrastructure.auth.sms.FarazSmsSender
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.AuthConfig
import com.kiva.infrastructure.config.BlogConfig
import com.kiva.infrastructure.config.DatasourceConfig
import com.kiva.infrastructure.config.SmsConfig
import com.kiva.infrastructure.config.SmsProvider
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AppModulesTest {

    // Only the database connection is used; the datasource settings are read at boot, before Koin.
    private val config = AppConfig(
        DatasourceConfig("jdbc:postgresql://localhost:5432/kiva_test", "unused", "unused", 1),
        auth = AuthConfig(otpPepper = ByteArray(32) { 7 }, jwtSecret = ByteArray(32) { 8 }, maxSmsPerHour = 300),
        blog = BlogConfig(voterPepper = ByteArray(32) { 9 }),
    )

    /** Everything is created at start, so a missing binding fails here instead of when the server boots. */
    @Test
    fun `the app's modules build the whole graph, domain services and use cases included`() {
        val app = koinApplication { modules(appModules(config, TestDatabase.database)) }
        try {
            assertNotNull(app.koin.get<ProductDomainService>())
            assertNotNull(app.koin.get<InventoryDomainService>())
            assertNotNull(app.koin.get<AdjustInventoryUc>())
            assertNotNull(app.koin.get<SendLoginCodeUc>())
            assertNotNull(app.koin.get<VerifyLoginCodeUc>())
            assertNotNull(app.koin.get<GetMeUc>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `with Faraz SMS the sender behind the port is Faraz's, and building it calls nobody`() {
        val faraz = SmsConfig(SmsProvider.FARAZ, FarazSmsConfig("https://api.iranpayamak.com", "TestOnlyFarazKey0123456789", "90008361", "vdXmsAfAQs"))
        val app = koinApplication { modules(appModules(config.copy(sms = faraz), TestDatabase.database)) }
        try {
            assertIs<FarazSmsSender>(app.koin.get<SmsSender>())
        } finally {
            app.close()
        }
    }
}
