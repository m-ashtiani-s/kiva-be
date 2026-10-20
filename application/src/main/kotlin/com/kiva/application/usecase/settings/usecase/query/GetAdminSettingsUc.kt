package com.kiva.application.usecase.settings.usecase.query

import com.kiva.application.dto.AdminSettingsDto
import com.kiva.application.port.PaymentGateways
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.Result
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository

/**
 * The panel's settings page (`GET /admin/settings`; ADMIN): always the whole set, every field, exactly what a `PUT`
 * takes back (roadmap phase 4, decision 1), with whether each gateway can take a payment now.
 */
class GetAdminSettingsUc(
    private val transactionManager: TransactionManager,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val paymentGateways: PaymentGateways,
) : QueryUseCase<GetAdminSettingsUc.Qry, AdminSettingsDto, Nothing> {

    private val logger = getLogger()

    /** @property by who reads, for the log only. */
    data class Qry(val by: UserId)

    override suspend fun execute(query: Qry): Result<AdminSettingsDto, Nothing> {
        logger.info("GetAdminSettingsUc.execute by=${query.by.value}")
        return transactionManager.inTransaction {
            val settings = shopSettingsRepository.get()
            val available = settings.paymentGateways.map { it.code }.filter { paymentGateways.isAvailable(it, settings) }.toSet()
            Result.success(AdminSettingsDto(settings, available))
        }
    }
}
