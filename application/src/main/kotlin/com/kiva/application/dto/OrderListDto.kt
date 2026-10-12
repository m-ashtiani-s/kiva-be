package com.kiva.application.dto

import com.kiva.common.pagination.Page

/**
 * «سفارش‌های من» (the contract's OrderListResponse): one page of the customer's order cards, newest first, and how many
 * orders each tab has, whichever tab is shown.
 */
data class OrderListDto(val page: Page<OrderSummaryDto>, val counts: Counts) {
    data class Counts(val all: Long, val current: Long, val delivered: Long, val cancelled: Long)
}
