package com.kiva.application.dto

import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * The shop's settings as the panel's settings page shows them (`GET /admin/settings`): the whole set, and which of its
 * gateways can take a payment now ([available]: switched on and with an adapter in this app).
 */
data class AdminSettingsDto(val settings: ShopSettings, val available: Set<PaymentGatewayCode>)
