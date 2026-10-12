package com.kiva.application.port

import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * The gateways this app has an adapter for. A gateway is **available** only when the shop's settings switch it on
 * **and** it has an adapter here (roadmap section 5.2): switching Saman on in the panel doesn't make it payable while no
 * Saman adapter exists.
 */
class PaymentGateways(gateways: List<PaymentGateway>) {

    private val byCode: Map<PaymentGatewayCode, PaymentGateway> = gateways.associateBy { it.code }

    init {
        require(byCode.size == gateways.size) { "Two adapters for one gateway: ${gateways.map { it.code }}" }
    }

    /** The adapter of [code]; null when the app has none. */
    fun of(code: PaymentGatewayCode): PaymentGateway? = byCode[code]

    /** [code] is switched on in [settings] and has an adapter. */
    fun isAvailable(code: PaymentGatewayCode, settings: ShopSettings): Boolean =
        code in byCode && settings.paymentGateways.any { it.code == code && it.isActive }
}
