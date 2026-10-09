package com.kiva.infrastructure.di

import com.kiva.application.port.OtpCodes
import com.kiva.application.port.SmsSender
import com.kiva.common.transaction.TransactionManager
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.auth.sms.LogSmsSender
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.SmsProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.koin.dsl.module
import java.time.Clock

/** Things built during boot that other components may need. Repositories, domain services and use cases have their own modules. */
fun infrastructureModule(config: AppConfig, database: Database) = module {
    single { config }
    single { database }
    single<TransactionManager> { ExposedTransactionManager(get()) }
    // Use cases read "now" from this, so tests can fix the time.
    single<Clock> { Clock.systemUTC() }
    single<HtmlSanitizer> { JsoupHtmlSanitizer }
    single<OtpCodes> { HmacOtpCodes(config.auth.otpPepper) }
    single<SmsSender> {
        when (config.sms.provider) {
            SmsProvider.LOG -> LogSmsSender()
        }
    }
}
