package com.kiva.domain.aggregate.discount.entity

import com.kiva.common.valueobject.Money
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountCodeId
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus.CONFIRMED
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus.HELD
import com.kiva.domain.aggregate.discount.model.valueobject.DiscountRedemptionStatus.RELEASED
import com.kiva.domain.aggregate.order.model.valueobject.OrderId
import java.time.Instant

/**
 * One order's use of a discount code (roadmap section 14): HELD from the placing, so it counts against the code's
 * limits while the order waits for its money; CONFIRMED when the order is paid (at once, with nothing to pay); RELEASED
 * when the order expires, giving the use back, or when a paid order is cancelled ([giveBack], phase 2). An order uses a
 * code once (a unique key on the order). The panel's `usedCount` and `totalDiscountGiven` are its CONFIRMED rows.
 *
 * Confirming and releasing go from HELD only, giving back from CONFIRMED only; any other (a second confirmation, a
 * release after the payment) changes nothing.
 *
 * @property amount what the code took off the order.
 * @property settledOn when it was confirmed or released.
 * @property storedStatus its status as it was read: the repository writes a change only over that one.
 */
class DiscountRedemption private constructor(
    val codeId: DiscountCodeId,
    val userId: UserId,
    val orderId: OrderId,
    val amount: Money,
    status: DiscountRedemptionStatus,
    val heldOn: Instant,
    settledOn: Instant?,
) {
    var status: DiscountRedemptionStatus = status
        private set
    var settledOn: Instant? = settledOn
        private set
    val storedStatus: DiscountRedemptionStatus = status

    init {
        require((status == HELD) == (settledOn == null)) { "Redemption of order ${orderId.value} is $status with settledOn=$settledOn" }
    }

    /** The order was paid. True when this confirmed it now. */
    fun confirm(now: Instant): Boolean = settle(CONFIRMED, now)

    /** The order expired: the use goes back to the code. True when this released it now. */
    fun release(now: Instant): Boolean = settle(RELEASED, now)

    /**
     * The paid order was cancelled: its use goes back to the code, so a code of one use may be used again. Only a
     * CONFIRMED use; true when this gave it back now.
     */
    fun giveBack(now: Instant): Boolean {
        if (status != CONFIRMED) return false
        status = RELEASED
        settledOn = now
        return true
    }

    private fun settle(to: DiscountRedemptionStatus, now: Instant): Boolean {
        if (status != HELD) return false
        status = to
        settledOn = now
        return true
    }

    override fun equals(other: Any?): Boolean = other is DiscountRedemption && other.orderId == orderId

    override fun hashCode(): Int = orderId.hashCode()

    override fun toString(): String = "DiscountRedemption(codeId=${codeId.value}, orderId=${orderId.value}, status=$status)"

    companion object {
        /** An order placed with a code [now]; [paid] when it had nothing to pay and was paid at once. */
        fun of(codeId: DiscountCodeId, userId: UserId, orderId: OrderId, amount: Money, paid: Boolean, now: Instant): DiscountRedemption =
            DiscountRedemption(codeId, userId, orderId, amount, if (paid) CONFIRMED else HELD, now, settledOn = now.takeIf { paid })

        fun constructFromPersisted(
            codeId: DiscountCodeId,
            userId: UserId,
            orderId: OrderId,
            amount: Money,
            status: DiscountRedemptionStatus,
            heldOn: Instant,
            settledOn: Instant?,
        ): DiscountRedemption = DiscountRedemption(codeId, userId, orderId, amount, status, heldOn, settledOn)
    }
}
