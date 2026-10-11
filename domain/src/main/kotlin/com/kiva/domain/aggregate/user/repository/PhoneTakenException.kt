package com.kiva.domain.aggregate.user.repository

import com.kiva.common.valueobject.IranianMobileNumber

/**
 * Saving a user was refused because another account took its phone after the caller checked it was free.
 *
 * The phone change flow checks first, so only two requests at the same moment get here: the database's unique key on
 * the phone has the last word. It is a race, not a bug, so it isn't left to end in a 500; but it is not a Result either,
 * because the repository can't go on after the database refused a write. The transaction rolls back; the use case
 * answers what its own check would have answered.
 */
class PhoneTakenException(val phone: IranianMobileNumber, cause: Throwable) :
    RuntimeException("Phone ${phone.masked()} was taken by another account", cause)
