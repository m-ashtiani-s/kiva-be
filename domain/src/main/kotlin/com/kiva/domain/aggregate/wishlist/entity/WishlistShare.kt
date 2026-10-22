package com.kiva.domain.aggregate.wishlist.entity

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.model.valueobject.WishlistShareToken
import java.time.Instant

/** The live public link of [userId]'s wishlist (decision 12): at most one; revoking deletes it, sharing again makes a new token. */
data class WishlistShare(val userId: UserId, val token: WishlistShareToken, val createdOn: Instant)
