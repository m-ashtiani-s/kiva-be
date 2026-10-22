package com.kiva.infrastructure.persistence.exposed

import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.wishlist.entity.WishlistShare
import com.kiva.domain.aggregate.wishlist.model.valueobject.WishlistShareToken
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareRepository
import com.kiva.domain.aggregate.wishlist.repository.WishlistShareTokenGenerator
import com.kiva.infrastructure.persistence.exposed.tbl.WishlistShareTbl
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.security.SecureRandom
import java.time.ZoneOffset

/** Runs inside the use case's transaction; it never opens one itself. */
class WishlistShareRepositoryImpl : WishlistShareRepository {

    override fun find(userId: UserId): WishlistShare? =
        WishlistShareTbl.selectAll().where { WishlistShareTbl.userId eq userId.value }.singleOrNull()?.toShare()

    override fun findByToken(token: WishlistShareToken): WishlistShare? =
        WishlistShareTbl.selectAll().where { WishlistShareTbl.token eq token.value }.singleOrNull()?.toShare()

    override fun insert(share: WishlistShare) {
        WishlistShareTbl.insert {
            it[userId] = share.userId.value
            it[token] = share.token.value
            it[createdOn] = share.createdOn.atOffset(ZoneOffset.UTC)
        }
    }

    override fun delete(userId: UserId) {
        WishlistShareTbl.deleteWhere { WishlistShareTbl.userId eq userId.value }
    }

    private fun ResultRow.toShare() = WishlistShare(
        UserId.constructFromPersisted(this[WishlistShareTbl.userId]),
        WishlistShareToken.constructFromPersisted(this[WishlistShareTbl.token]),
        this[WishlistShareTbl.createdOn].toInstant(),
    )
}

/** `wl_` and 32 base62 characters from [SecureRandom]: about 190 bits, never guessed. */
class RandomWishlistShareTokens(private val random: SecureRandom = SecureRandom()) : WishlistShareTokenGenerator {
    override fun next(): WishlistShareToken {
        val chars = CharArray(WishlistShareToken.RANDOM_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }
        return WishlistShareToken.constructFromPersisted(WishlistShareToken.PREFIX + String(chars))
    }

    private companion object {
        const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    }
}
