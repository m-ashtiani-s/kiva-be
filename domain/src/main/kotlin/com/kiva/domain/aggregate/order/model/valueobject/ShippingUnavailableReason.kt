package com.kiva.domain.aggregate.order.model.valueobject

/**
 * Why a shipping option is shown but can't be chosen. Only the code; the Persian text (`unavailableReason` of the
 * contract's ShippingOptionQuote) is the HTTP layer's job.
 */
enum class ShippingUnavailableReason {
    /** Round 2: the order joins a held reservation, which ships by another method («سفارش رزروی با پست ارسال می‌شه»). */
    CONSOLIDATED_WITH_RESERVATION,
}
