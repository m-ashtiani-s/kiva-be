package com.kiva.application.dto

import com.kiva.application.port.PaymentGateways
import com.kiva.domain.aggregate.user.model.valueobject.MessengerChannel

/**
 * The checkout page's data (the contract's CheckoutContext), without its fixed parts and Persian texts: the messengers'
 * names and hints, the gift wrap and the terms page are the HTTP layer's. The contract's top-level shipping options,
 * reservation and consolidation are the [cart]'s own: one quote for both, so they never disagree.
 *
 * Personal data: [toString] shows ids only.
 *
 * @property addresses the default first, then oldest first, as the address book lists them.
 * @property selectedAddressId the address checkout starts with: the one asked for, else the default; null when the
 *   book is empty, and the page opens the form for a new address.
 * @property selectedMessenger the customer's default messenger (an order with `saveAsDefault` keeps its choice there);
 *   null until there is one.
 * @property messengerPhone the number the photo goes to: the one kept with the default messenger, else the account's
 *   mobile.
 * @property paymentGateways every gateway the shop has, in list order, available or not (PaymentGateways).
 */
data class CheckoutContextDto(
    val cart: CartDto,
    val addresses: List<AddressDto>,
    val selectedAddressId: Long?,
    val selectedMessenger: MessengerChannel?,
    val messengerPhone: String,
    val paymentGateways: List<PaymentGateways.CheckoutOption>,
) {
    override fun toString(): String =
        "CheckoutContextDto(cartId=${cart.id}, addresses=${addresses.map { it.id }}, selectedAddressId=$selectedAddressId, selectedMessenger=$selectedMessenger)"
}
