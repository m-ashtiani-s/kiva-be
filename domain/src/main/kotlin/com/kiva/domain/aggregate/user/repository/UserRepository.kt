package com.kiva.domain.aggregate.user.repository

import com.kiva.common.valueobject.IranianMobileNumber
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.user.entity.User
import java.time.Instant

/** Where users are kept: one row each, with a version for optimistic locking. */
interface UserRepository {
    /** The user, a deleted one ([User.isDeleted]) too: whoever needs a live account checks it. */
    fun findById(id: UserId): User?

    /**
     * The user with their row locked until the transaction ends (`FOR UPDATE`): deleting the account holds it, so nothing
     * else saves the account meanwhile (roadmap section 19.6, the last of DELETE /me's locks).
     */
    fun lockAndFind(id: UserId): User?

    /** Whether the account [id] was deleted; null when there is no such account. One column, for every logged-in request. */
    fun isDeleted(id: UserId): Boolean?

    /** The live account with [phone]: a deleted one has none. */
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

    /** How many users have the ADMIN role now. Read under [lockRoleChanges], it can't change before the commit. */
    fun countAdmins(): Int

    /**
     * Makes changes of roles one at a time, until the transaction ends (section 4.11, lock 0): two admins taking ADMIN from
     * each other at once see each other's change, so the last ADMIN is never lost (roadmap phase 4, decision 14).
     */
    fun lockRoleChanges()

    /** @property isNew the account was made by this call, so the client asks the new customer for their name. */
    data class FoundOrRegistered(val user: User, val isNew: Boolean)
}
