package com.kiva.application.port

import com.kiva.domain.aggregate.settings.entity.PaymentGatewayOption
import com.kiva.domain.aggregate.settings.entity.ShopSettings
import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * The gateways this app has an adapter for. A gateway is **available** only when the shop's settings switch it on
 * **and** it has an adapter here (roadmap section 5.2): switching Saman on in the panel doesn't make it payable while no
 * Saman adapter exists. Checkout lists the gateways by this and an order is placed by it, so the page never offers a
 * gateway the order then refuses.
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

    /**
     * Every gateway the shop has a row for, in list order, as checkout shows them: the unavailable ones too, greyed out.
     * The one checkout selects first is the shop's default while it is available, otherwise the first available one, so
     * the page never starts on a gateway that can't take the payment; none when no gateway is available.
     */
    fun checkoutOptions(settings: ShopSettings): List<CheckoutOption> {
        val available = settings.paymentGateways.filter { isAvailable(it.code, settings) }
        val selectedFirst = available.firstOrNull { it.isDefault } ?: available.firstOrNull()
        return settings.paymentGateways.map { CheckoutOption(it, available = it in available, isDefault = it == selectedFirst) }
    }

    /** One gateway on the checkout page: the shop's [gateway] (name and look), whether it can take a payment now, and whether it is selected first. */
    data class CheckoutOption(val gateway: PaymentGatewayOption, val available: Boolean, val isDefault: Boolean)
}
