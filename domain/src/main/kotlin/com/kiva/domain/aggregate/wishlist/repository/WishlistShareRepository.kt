package com.kiva.domain.aggregate.wishlist.repository

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.entity.WishlistShare
import com.kiva.domain.aggregate.wishlist.model.valueobject.WishlistShareToken

/** The public links of wishlists, at most one per customer. Making one runs under the list's lock (lock 13). */
interface WishlistShareRepository {

    /** [userId]'s live link, or null. */
    fun find(userId: UserId): WishlistShare?

    /** The live link with [token], or null: never made, or revoked. */
    fun findByToken(token: WishlistShareToken): WishlistShare?

    fun insert(share: WishlistShare)

    /** Revokes [userId]'s link; nothing to revoke is fine. */
    fun delete(userId: UserId)
}

/** Hands out new share tokens: `wl_` and 32 characters from a secure random source. */
interface WishlistShareTokenGenerator {
    fun next(): WishlistShareToken
}
