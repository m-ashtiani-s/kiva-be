package com.kiva.domain.aggregate.returnrequest.model.policy

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.order.entity.OrderLine
import com.kiva.domain.aggregate.order.model.policy.ChangeRequestPolicy
import com.kiva.domain.aggregate.order.model.valueobject.OrderTotals
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnReceipt

/**
 * The money a received return suggests giving back (`AdminReturnRequest.suggestedRefund`; roadmap section 12.2, decision
 * 16), so the admin doesn't work it out with a calculator. Pure. Only a suggestion: the admin types the amount (less the
 * pickup's cost when the customer pays it, say).
 */
object ReturnRefundPolicy {

    /**
     * For each line of [receipt], what the customer paid for the part of it that came back:
     * `floor(paidShareOf(line) × received ÷ line.quantity)` (paidShareOf takes the discount code's share off, as for a line
     * a change request took out); all of it together, at most [refundable] (what the order's money still allows).
     * Shipping never comes back. [lines] are the order's lines now, [placedTotals] its placed totals.
     */
    fun suggestedRefund(lines: List<OrderLine>, placedTotals: OrderTotals, receipt: ReturnReceipt, refundable: Money): Money {
        val byId = lines.associateBy { it.lineId }
        val total = receipt.lines.sumOf { received ->
            val line = byId[received.lineId] ?: error("A receipt of line ${received.lineId}, which the order doesn't have")
            check(received.received <= line.quantity.value) { "Line ${line.lineId}: ${received.received} came back of ${line.quantity.value}" }
            // At most a line's paid share (a few billion toman) times 20: well inside a Long.
            Math.multiplyExact(ChangeRequestPolicy.paidShareOf(line, placedTotals).toman, received.received.toLong()) / line.quantity.value
        }
        return minOf(Money.constructFromPersisted(total), refundable)
    }
}
