package com.kiva.application.port

import com.kiva.application.dto.InvoiceDto

/**
 * Makes the PDF of a purchase receipt (roadmap phase 14, decision 15). Behind a port because it is the riskiest piece
 * of the phase (Persian text in a PDF) and may be replaced; and because it takes time, the use case calls it outside
 * its transaction.
 */
interface InvoiceRenderer {
    /** The PDF's bytes: one A4 page, right to left. */
    fun render(invoice: InvoiceDto): ByteArray
}
