package com.kiva.domain.aggregate.settings.entity

import com.kiva.domain.aggregate.settings.model.valueobject.PaymentGatewayCode

/**
 * A payment gateway as the shop has set it up (name and look on the checkout page, switched on or off). Part of
 * [ShopSettings].
 *
 * Switched on is not enough to be offered: the application must also have an adapter for the gateway. That second
 * half is the application's business, not the settings'.
 *
 * Its identity is [code].
 *
 * @property brandColor and [initial]: the logo's stand-in when there is no [logoUrl], one letter on the brand color.
 * @property isDefault the gateway checkout selects first; at most one, and only an active one (database rules).
 * @property sortOrder position among the gateways, smallest first.
 */
class PaymentGatewayOption private constructor(
    val code: PaymentGatewayCode,
    val name: String,
    val brandColor: String,
    val initial: String,
    val logoUrl: String?,
    val isActive: Boolean,
    val isDefault: Boolean,
    val sortOrder: Int,
) {
    init {
        require(name.isNotBlank() && initial.isNotBlank()) { "A gateway needs a name and an initial ($code)" }
    }

    override fun equals(other: Any?): Boolean = other is PaymentGatewayOption && other.code == code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "PaymentGatewayOption(code=$code, isActive=$isActive, isDefault=$isDefault)"

    companion object {
        fun constructFromPersisted(
            code: PaymentGatewayCode,
            name: String,
            brandColor: String,
            initial: String,
            logoUrl: String?,
            isActive: Boolean,
            isDefault: Boolean,
            sortOrder: Int,
        ): PaymentGatewayOption = PaymentGatewayOption(code, name, brandColor, initial, logoUrl, isActive, isDefault, sortOrder)
    }
}
