package com.kiva.application.usecase.order.usecase.query

import com.kiva.application.dto.InvoiceDto
import com.kiva.application.port.InvoiceRenderer
import com.kiva.application.repository.SiteContentRepository
import com.kiva.application.usecase.order.OrderByCode
import com.kiva.application.usecase.order.OrderDetails
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.order.model.valueobject.OrderStatus
import com.kiva.domain.aggregate.settings.repository.ShopSettingsRepository
import java.time.Clock

/**
 * A purchase receipt as a PDF (`GET /me/orders/{orderCode}/invoice`, roadmap phase 14, section 19.6): **not** an official
 * tax invoice (decision 14). Only the order's own customer (another's or none is 404 ORDER_NOT_FOUND), only an order
 * that was paid once, so a cancelled or returned one too (decision 13); one never paid is 422 INVOICE_NOT_AVAILABLE.
 *
 * The order is read in a transaction, as its page reads it (its current lines and totals); the PDF is made **after**
 * the transaction ends (decision 15, section 27): drawing a page takes time, and a connection is not held for it.
 */
class GetMyInvoiceUc(
    private val transactionManager: TransactionManager,
    private val orderByCode: OrderByCode,
    private val orderDetails: OrderDetails,
    private val shopSettingsRepository: ShopSettingsRepository,
    private val siteContentRepository: SiteContentRepository,
    private val invoiceRenderer: InvoiceRenderer,
    private val siteUrl: String,
    private val clock: Clock,
) : QueryUseCase<GetMyInvoiceUc.Qry, GetMyInvoiceUc.Invoice, GetMyInvoiceUc.Failure> {

    private val logger = getLogger()

    /**
     * @property userId from the access token, never from the request.
     * @property orderCode the path's code, as it came.
     */
    data class Qry(val userId: UserId, val orderCode: String)

    /** The receipt of the order [code]: its PDF. */
    class Invoice(val code: String, val pdf: ByteArray) {
        override fun toString(): String = "Invoice(code=$code, bytes=${pdf.size})"
    }

    override suspend fun execute(query: Qry): Result<Invoice, Failure> {
        val code = orderByCode.codeOf(query.orderCode)
        logger.info("GetMyInvoiceUc.execute userId=${query.userId.value} orderCode=${code?.value ?: "(not a code)"}")
        if (code == null) return Result.failure(Failure.OrderNotFound)

        val invoice = transactionManager.inTransaction<InvoiceDto, Failure> {
            val order = orderByCode.findOfCustomer(query.userId, query.orderCode) ?: return@inTransaction Result.failure(Failure.OrderNotFound)
            val paidOn = order.paidOn ?: return@inTransaction Result.failure(Failure.NotAvailable)
            val settings = shopSettingsRepository.get()
            val seller = InvoiceDto.Seller(siteContentRepository.load().brand.name, settings.supportPhone?.value, settings.supportEmail?.value, siteUrl)
            Result.success(InvoiceDto(orderDetails.of(order, settings, clock.instant()), seller, paidOn, cancelled = order.status == OrderStatus.CANCELLED))
        }.unwrapOr { return Result.failure(it) }

        return Result.success(Invoice(code.value, invoiceRenderer.render(invoice)))
    }

    sealed class Failure(code: String, kind: FailureKind) : ResultFailure(code, kind) {
        data object OrderNotFound : Failure("ORDER_NOT_FOUND", FailureKind.NOT_FOUND)

        /** Never paid: there is no receipt of it yet. */
        data object NotAvailable : Failure("INVOICE_NOT_AVAILABLE", FailureKind.BUSINESS_RULE)
    }
}
