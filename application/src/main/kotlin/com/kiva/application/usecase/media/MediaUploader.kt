package com.kiva.application.usecase.media

import com.kiva.common.valueobject.UserId

/**
 * Who uploads a file, from the route that took it: it decides the purposes they may upload with and whether the
 * customer's hourly cap applies (roadmap section 12.2, row 23). The processing is the same for both, to the letter:
 * a customer's upload is more dangerous than staff's, not less.
 */
sealed interface MediaUploader {
    val userId: UserId

    /** Anyone of the panel's upload roles (`POST /admin/media`): the staff purposes, no cap. */
    data class Staff(override val userId: UserId) : MediaUploader

    /** A logged-in customer (`POST /me/uploads`): the customer purposes, at most 20 an hour. */
    data class Customer(override val userId: UserId) : MediaUploader
}
