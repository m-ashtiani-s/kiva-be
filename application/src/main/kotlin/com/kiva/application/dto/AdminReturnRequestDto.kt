package com.kiva.application.dto

import com.kiva.common.valueobject.Money
import com.kiva.domain.aggregate.returnrequest.model.valueobject.ReturnStatus

/**
 * A return in the panel (the contract's AdminReturnRequest; roadmap section 12.2, decision 22): everything the customer
 * sees ([ret]), and the customer, what the check of the parcel found, where it may go next and the money its receipt
 * suggests giving back. No IBAN: it is taken on the phone and never stored (roadmap section 1, row 4).
 *
 * @property allowedTransitions where it may go now, with its refunds counted (a RECEIVED return with a refund open: none).
 * @property suggestedRefund only once RECEIVED (ReturnRefundPolicy); null otherwise.
 */
data class AdminReturnRequestDto(
    val ret: ReturnRequestDto,
    val customer: Customer,
    val receipt: Receipt?,
    val allowedTransitions: Set<ReturnStatus>,
    val suggestedRefund: Money?,
) {
    override fun toString(): String = "AdminReturnRequestDto(id=${ret.id}, status=${ret.status})"

    /** The customer's account as it is now. Personal data: [toString] shows the id only. */
    data class Customer(val id: Long, val fullName: String?, val phone: String) {
        override fun toString(): String = "Customer(id=$id)"
    }

    /** What came back of each line, and staff's internal [note] (the customer never sees it). */
    data class Receipt(val lines: List<Line>, val note: String?) {
        override fun toString(): String = "Receipt(lines=$lines)"
    }

    /** [received] of the line [lineId] came back, [restock] of them back on the shelf. */
    data class Line(val lineId: Int, val received: Int, val restock: Int)
}
