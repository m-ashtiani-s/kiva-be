package com.kiva.domain.aggregate.settings.model.valueobject

/**
 * How many working days a shipping method takes, from the day of shipping: «۳ تا ۵ روز کاری» is 3 to 5.
 * Fridays and the shop's other non-shipping days don't count.
 *
 * Only read back from the settings for now; a `makeNew` with its failures comes with the admin panel's settings page.
 */
@ConsistentCopyVisibility
data class DeliveryDays private constructor(val min: Int, val max: Int) {

    // The same rules as shippingmethod_tbl's CHECKs: a broken row is a bug, stop loudly.
    init {
        require(min in 0..max && max <= MAX) { "Invalid DeliveryDays $min..$max" }
    }

    override fun toString(): String = "DeliveryDays($min..$max)"

    companion object {
        const val MAX = 30

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(min: Int, max: Int): DeliveryDays = DeliveryDays(min, max)
    }
}
