package com.kiva.application.dto

import java.time.Instant

/**
 * A held «رزرو ۴ روزه» (the contract's ReservationInfo), without its Persian message: the countdown's numbers and the
 * orders in it.
 *
 * @property orderCode the order that started it.
 * @property expiresAt its first order's payment plus the hold days: fixed for the whole group.
 * @property remainingSeconds until [expiresAt], never below 0.
 * @property groupOrderCodes its paid orders, oldest first.
 */
data class ReservationInfoDto(
    val active: Boolean,
    val orderCode: String,
    val startedAt: Instant,
    val expiresAt: Instant,
    val remainingSeconds: Long,
    val groupOrderCodes: List<String>,
)
