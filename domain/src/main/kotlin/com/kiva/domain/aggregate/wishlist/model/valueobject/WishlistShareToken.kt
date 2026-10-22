package com.kiva.domain.aggregate.wishlist.model.valueobject

/**
 * The secret of a wishlist's public link: `wl_` and 32 random base62 characters (decision 12). Whoever has it sees the
 * list, so it is never logged whole ([toString] shows its first 6 characters).
 */
@JvmInline
value class WishlistShareToken private constructor(val value: String) {

    init {
        require(FORMAT.matches(value)) { "Invalid WishlistShareToken" }
    }

    override fun toString(): String = "WishlistShareToken(${value.take(6)}…)"

    companion object {
        const val PREFIX = "wl_"
        const val RANDOM_LENGTH = 32
        private val FORMAT = Regex("wl_[0-9A-Za-z]{32}")

        /** A token from a path as it came; null when it can't be one (the same 404 as an unknown token). */
        fun makeNew(raw: String): WishlistShareToken? = raw.takeIf { FORMAT.matches(it) }?.let(::WishlistShareToken)

        fun constructFromPersisted(value: String): WishlistShareToken = WishlistShareToken(value)
    }
}
