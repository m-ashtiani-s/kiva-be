package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.forField
import com.kiva.common.railway.requiredIn
import com.kiva.common.railway.zip

/**
 * How many working days a shipping method takes, from the day of shipping: «۳ تا ۵ روز کاری» is 3 to 5.
 * Fridays and the shop's other non-shipping days don't count.
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

        /**
         * The panel's `minDays` and `maxDays`, both problems at once under their own fields: [min] 0 to 30, and [max]
         * from [min] to 30 (from 0 while [min] itself is wrong); NUMBER_OUT_OF_RANGE with the range.
         */
        fun makeNew(min: Int?, max: Int?): Result<DeliveryDays, InputProblems> {
            val checkedMin = min.requiredIn(0..MAX).forField("minDays")
            val lowestMax = checkedMin.getOrNull() ?: 0
            return Result.zip(checkedMin, max.requiredIn(lowestMax..MAX).forField("maxDays"), ::InputProblems) { from, to -> DeliveryDays(from, to) }
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(min: Int, max: Int): DeliveryDays = DeliveryDays(min, max)
    }
}
