package com.kiva.application.usecase.stockalert

import com.kiva.application.dto.StockAlertDto
import com.kiva.application.repository.ProductQmRepository
import com.kiva.domain.aggregate.color.repository.ColorRepository
import com.kiva.domain.aggregate.stockalert.entity.StockAlert
import com.kiva.domain.aggregate.user.repository.UserRepository

/**
 * A customer's subscriptions as «اشتراک‌های من» and the answer of subscribing show them (Rule 15): the product (any
 * status: one taken off the shop still has a name), the color waited for from the palette, and the account's phone,
 * masked, read now. Three reads for a whole list. Inside the caller's transaction.
 */
class StockAlerts(
    private val productQmRepository: ProductQmRepository,
    private val colorRepository: ColorRepository,
    private val userRepository: UserRepository,
) {

    fun of(alerts: List<StockAlert>): List<StockAlertDto> {
        if (alerts.isEmpty()) return emptyList()
        val products = productQmRepository.findBriefs(alerts.map { it.productId }.distinct())
        val variants = productQmRepository.findVariants(alerts.mapNotNull { it.variantId }.distinct())
        val palette = colorRepository.findAll().associateBy { it.key }
        val phones = alerts.map { it.userId }.distinct().associateWith { userRepository.findById(it)?.phone?.masked() }
        return alerts.mapNotNull { alert ->
            // The read model keeps every product ever made; a row without one is a product that never was.
            val product = products[alert.productId] ?: return@mapNotNull null
            val colorKey = alert.variantId?.let { variants[it]?.colorKey }
            StockAlertDto(
                id = alert.id.value,
                product = product,
                variantId = alert.variantId?.value,
                color = colorKey?.let { palette[it] },
                phoneMasked = phones[alert.userId].orEmpty(),
                status = alert.status,
                createdAt = alert.createdOn,
                notifiedAt = alert.notifiedOn,
            )
        }
    }
}
