package com.kiva.domain.aggregate.user.repository

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant

/** Where users are kept: one row each, with a version for optimistic locking. */
interface UserRepository {
    fun findById(id: UserId): User?

    fun findByPhone(phone: IranianMobileNumber): User?

    /**
     * The user with this phone, registered now ([User.register]) if there is none: what a successful login code leads
     * to. Two logins of a new phone at the same moment make one account; only one of them gets [FoundOrRegistered.isNew].
     */
    fun findOrRegister(phone: IranianMobileNumber, now: Instant): FoundOrRegistered

    /**
     * Saves a change to a user that was read before. Throws ConcurrentChangeException when someone else saved a change
     * to it after it was read, and [PhoneTakenException] when its new phone was taken by another account meanwhile:
     * either way nothing of this change is stored. The stored version goes up by one, so read the user again to change
     * it again.
     */
    fun save(user: User)

    /** At least one user has the ADMIN role. The first admin comes from the environment only while this is false. */
    fun hasAnyAdmin(): Boolean

    /** @property isNew the account was made by this call, so the client asks the new customer for their name. */
    data class FoundOrRegistered(val user: User, val isNew: Boolean)
}
