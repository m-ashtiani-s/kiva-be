package com.kiva.infrastructure.di

import com.kiva.application.port.InvoiceRenderer
import com.kiva.infrastructure.job.EngagementJobs
import com.kiva.application.port.AccessTokens
import com.kiva.application.port.HtmlText
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.MediaStorage
import com.kiva.application.port.OtpCodes
import com.kiva.application.port.PaymentGateways
import com.kiva.application.port.SessionTokens
import com.kiva.application.port.SmsSender
import com.kiva.application.port.EmailSender
import com.kiva.infrastructure.email.EmailProvider
import com.kiva.infrastructure.email.LogEmailSender
import com.kiva.infrastructure.email.SmtpEmailSender
import com.kiva.common.transaction.TransactionManager
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import com.kiva.infrastructure.auth.HmacOtpCodes
import com.kiva.infrastructure.auth.JwtAccessTokens
import com.kiva.infrastructure.auth.RandomSessionTokens
import com.kiva.infrastructure.auth.sms.FarazSmsSender
import com.kiva.infrastructure.auth.sms.LogSmsSender
import com.kiva.infrastructure.common.html.JsoupHtmlSanitizer
import com.kiva.infrastructure.common.html.JsoupHtmlText
import com.kiva.infrastructure.common.persistence.ExposedTransactionManager
import com.kiva.infrastructure.config.AppConfig
import com.kiva.infrastructure.config.SmsProvider
import com.kiva.infrastructure.payment.FakePaymentGateway
import com.kiva.infrastructure.payment.PaymentProvider
import com.kiva.infrastructure.pdf.PdfBoxInvoiceRenderer
import org.jetbrains.exposed.v1.jdbc.Database
import com.kiva.infrastructure.job.JobScheduler
import com.kiva.infrastructure.payment.ZarinpalGateway
import io.ktor.client.engine.cio.CIO
import com.kiva.infrastructure.job.PanelJobs
import com.kiva.infrastructure.media.DiskMediaStorage
import com.kiva.infrastructure.media.MediaUrls
import com.kiva.infrastructure.media.VipsFfmpegMediaProcessor
import com.kiva.infrastructure.job.PurchaseJobs
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
    single<HtmlText> { JsoupHtmlText }
    single<OtpCodes> { HmacOtpCodes(config.auth.otpPepper) }
    // One instance: it signs tokens for the use cases and its verifier checks them for the routes.
    single { JwtAccessTokens(config.auth.jwtSecret, clock = get()) }
    single<AccessTokens> { get<JwtAccessTokens>() }
    single<SessionTokens> { RandomSessionTokens() }
    single<SmsSender> {
        when (config.sms.provider) {
            SmsProvider.LOG -> LogSmsSender()
            SmsProvider.FARAZ -> FarazSmsSender(checkNotNull(config.sms.faraz), CIO.create())
        }
    }
    single<EmailSender> {
        when (config.email.provider) {
            EmailProvider.LOG -> LogEmailSender()
            EmailProvider.SMTP -> SmtpEmailSender(checkNotNull(config.email.smtp))
        }
    }
    // The scheduled jobs; started by Application.start once the app has started, when kiva.jobs.enabled.
    single {
        PurchaseJobs(
            findExpirableOrders = get(), expireUnpaidOrder = get(), findStalePayments = get(), reconcilePayment = get(),
            deleteStaleGuestCarts = get(), deleteOldIdempotencyKeys = get(), findDueReservationGroups = get(), releaseReservationGroup = get(),
        )
    }
    // Uploads: processed by libvips and ffmpeg, kept on this server's disk, served from kiva.media.publicBaseUrl.
    single { DiskMediaStorage(config.media.root) }
    single<MediaStorage> { get<DiskMediaStorage>() }
    single<MediaProcessor> { VipsFfmpegMediaProcessor(config.media) }
    // The purchase receipt (phase 14): PDFBox with the fonts of the resources, read once.
    single<InvoiceRenderer> { PdfBoxInvoiceRenderer() }
    single { MediaUrls(config.media.publicBaseUrl) }
    single {
        PanelJobs(
            deleteOldAuditEntries = get(), findUnattachedMedia = get(), deleteUnattachedMedia = get(),
            findDueScheduledProducts = get(), publishScheduledProduct = get(), findSilentPhotoOrders = get(), approveSilentPhoto = get(),
        )
    }
    single {
        EngagementJobs(
            findDueStockAlerts = get(), notifyStockAlert = get(), deleteOldStockAlerts = get(), deleteOldContactData = get(), smsSender = get(), clock = get(),
        )
    }
    single { JobScheduler(get<PurchaseJobs>().all() + get<PanelJobs>().all() + get<EngagementJobs>().all(), clock = get()) }
    // The adapters behind the gateways. A gateway without one here is never available, whatever the settings say.
    when (config.payment.provider) {
        PaymentProvider.FAKE -> {
            single { FakePaymentGateway(config.payment) }
            single { PaymentGateways(listOf(get<FakePaymentGateway>())) }
        }
        PaymentProvider.ZARINPAL -> {
            single { ZarinpalGateway(config.payment, CIO.create()) }
            single { PaymentGateways(listOf(get<ZarinpalGateway>())) }
        }
    }
}
