package com.kiva.application.usecase.content.usecase.query

import com.kiva.application.dto.AnnouncementDto
import com.kiva.application.dto.SiteConfigDto
import com.kiva.application.port.PaymentGateways
import com.kiva.application.repository.SiteContentRepository
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import com.kiva.domain.aggregate.otp.model.policy.OtpPolicy
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * The site's settings for everyone (`GET /config`, public, cached for 5 minutes; roadmap phase 5, step 5.4): the site's
 * own texts, the shop's settings (the same rows the cart and checkout read, so every number has one source), the
 * gateways as checkout shows them, the announcements whose time it is, and whether someone answers now, by Tehran's
 * clock. One transaction, so they are all of one moment.
 */
class GetSiteConfigUc(
    private val transactionManager: TransactionManager,
    private val siteContentRepository: SiteContentRepository,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val paymentGateways: PaymentGateways,
    private val announcementBarRepository: AnnouncementBarRepository,
    private val clock: Clock,
) : QueryUseCase<GetSiteConfigUc.Qry, SiteConfigDto, Nothing> {

    private val logger = getLogger()

    data object Qry

    override suspend fun execute(query: Qry): Result<SiteConfigDto, Nothing> {
        logger.info("GetSiteConfigUc.execute")
        val now = clock.instant()
        val local = now.atZone(ShopCalendar.ZONE)
        return transactionManager.inTransaction {
            val settings = shopSettingsRepository.get()
            Result.success(
                SiteConfigDto(
                    content = siteContentRepository.load(),
                    settings = settings,
                    gateways = paymentGateways.checkoutOptions(settings),
                    announcements = announcementBarRepository.load().showingAt(now).map(AnnouncementDto::from),
                    supportOpenNow = settings.supportHours.any { it.isOpenAt(local.dayOfWeek, local.toLocalTime()) },
                    otpLength = OtpPolicy.CODE_LENGTH,
                    resendCooldownSeconds = OtpPolicy.RESEND_AFTER.seconds,
                ),
            )
        }
    }
}
