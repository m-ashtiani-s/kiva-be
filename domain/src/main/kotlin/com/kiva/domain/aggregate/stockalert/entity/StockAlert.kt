package com.kiva.domain.aggregate.stockalert.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.product.model.valueobject.ProductId
import com.kiva.domain.aggregate.product.model.valueobject.VariantId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertId
import com.kiva.domain.aggregate.stockalert.model.valueobject.StockAlertStatus
import java.time.Instant

/**
 * «موجود شد خبرم کن» (roadmap section 15.3): a customer waiting for a color of a product ([variantId]), or for any of
 * its colors (null), to be back. ACTIVE until the job tells them (NOTIFIED, once) or they call it off (CANCELLED); an
 * ended one is kept (decision 6) until the nightly clean-up takes it after 90 days. The SMS goes to the account's own
 * phone, read when it is sent: none is kept here.
 */
class StockAlert private constructor(
    val id: StockAlertId,
    val userId: UserId,
    val productId: ProductId,
    val variantId: VariantId?,
    status: StockAlertStatus,
    val createdOn: Instant,
    notifiedOn: Instant?,
    notifiedVariantId: VariantId?,
    cancelledOn: Instant?,
) {
    var status: StockAlertStatus = status
        private set
    var notifiedOn: Instant? = notifiedOn
        private set
    var notifiedVariantId: VariantId? = notifiedVariantId
        private set
    var cancelledOn: Instant? = cancelledOn
        private set

    // The same rules as V35's CHECKs: a broken row is a bug.
    init {
        require((status == StockAlertStatus.NOTIFIED) == (notifiedOn != null && notifiedVariantId != null)) { "Stock alert ${id.value}: NOTIFIED needs its moment and color" }
        require((status == StockAlertStatus.CANCELLED) == (cancelledOn != null)) { "Stock alert ${id.value}: CANCELLED needs its moment" }
    }

    /** Calls it off: only an ACTIVE one; an ended one stays as it is (false). */
    fun cancel(now: Instant): Boolean {
        if (status != StockAlertStatus.ACTIVE) return false
        status = StockAlertStatus.CANCELLED
        cancelledOn = now
        return true
    }

    /** The job told the customer [variantId] is back. Only an ACTIVE one: the job checks under the row's lock first. */
    fun markNotified(variantId: VariantId, now: Instant) {
        check(status == StockAlertStatus.ACTIVE) { "Stock alert ${id.value} is $status, not ACTIVE" }
        status = StockAlertStatus.NOTIFIED
        notifiedOn = now
        notifiedVariantId = variantId
    }

    override fun toString(): String = "StockAlert(id=${id.value}, productId=${productId.value}, variantId=${variantId?.value}, status=$status)"

    companion object {
        /** A new subscription: always ACTIVE. The rules on who may subscribe are StockAlertDomainService's. */
        fun subscribe(id: StockAlertId, userId: UserId, productId: ProductId, variantId: VariantId?, now: Instant) =
            StockAlert(id, userId, productId, variantId, StockAlertStatus.ACTIVE, now, null, null, null)

        fun constructFromPersisted(
            id: StockAlertId,
            userId: UserId,
            productId: ProductId,
            variantId: VariantId?,
            status: StockAlertStatus,
            createdOn: Instant,
            notifiedOn: Instant?,
            notifiedVariantId: VariantId?,
            cancelledOn: Instant?,
        ) = StockAlert(id, userId, productId, variantId, status, createdOn, notifiedOn, notifiedVariantId, cancelledOn)
    }
}
