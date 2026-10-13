package com.kiva.domain.aggregate.order.model.valueobject

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.settings.model.valueobject.ShippingMethodCode

/**
 * The «فقط ۱٬۵۵۰٬۰۰۰ تومان تا ارسال رایگان با پست» bar (the contract's FreeShippingProgress, without the Persian
 * message). Made by ShippingPolicy.
 *
 * @property method the method that ships free once [threshold] is reached.
 * @property remaining how much more the subtotal needs; 0 once [eligible].
 * @property progressPerMille how full the bar is, in tenths of a percent: 483 is the contract's `progressPercent` 48.3.
 *   A whole number, so no Double ever touches it; the HTTP layer writes it with one decimal. It is 1000 only when
 *   [eligible]: a bar that looks full while a few toman are still missing would contradict its own message.
 */
data class FreeShippingProgress(
    val threshold: Money,
    val method: ShippingMethodCode,
    val eligible: Boolean,
    val remaining: Money,
    val progressPerMille: Int,
) {
    init {
        require(progressPerMille in 0..FULL) { "Invalid progress $progressPerMille‰" }
        require(eligible == (remaining == Money.ZERO)) { "Eligible is $eligible with $remaining remaining" }
        require(eligible == (progressPerMille == FULL)) { "Eligible is $eligible at $progressPerMille‰" }
    }

    companion object {
        /** A full bar: 100.0%. */
        const val FULL = 1000
    }
}
