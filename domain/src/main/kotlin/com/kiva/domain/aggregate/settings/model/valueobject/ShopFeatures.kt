package com.kiva.domain.aggregate.settings.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.FieldRequired
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.under

/**
 * The shop's feature switches (the contract's `features`), only the ones the shop knows (roadmap phase 4, decision 6):
 * [wishlistShare] (sharing a wishlist by link, phase 9), [reviewMedia] (photos in reviews, phase 8) and [giftWrap],
 * which nothing behind it does yet, so it is always off.
 */
@ConsistentCopyVisibility
data class ShopFeatures private constructor(val wishlistShare: Boolean, val reviewMedia: Boolean, val giftWrap: Boolean) {

    init {
        require(!giftWrap) { "Gift wrapping can't be on: nothing does it yet" }
    }

    /** Every switch by the contract's key, in the contract's order. */
    fun byKey(): Map<String, Boolean> = mapOf(WISHLIST_SHARE to wishlistShare, REVIEW_MEDIA to reviewMedia, GIFT_WRAP to giftWrap)

    companion object {
        const val WISHLIST_SHARE = "wishlistShare"
        const val REVIEW_MEDIA = "reviewMedia"
        const val GIFT_WRAP = "giftWrap"
        val KEYS = listOf(WISHLIST_SHARE, REVIEW_MEDIA, GIFT_WRAP)

        /** Every switch off: V30's default. */
        val NONE = ShopFeatures(wishlistShare = false, reviewMedia = false, giftWrap = false)

        /**
         * The switches as the panel sent them, every problem at once under its key: a key the shop doesn't know is
         * FEATURE_UNKNOWN, a known one left out FIELD_REQUIRED, [GIFT_WRAP] on FEATURE_NOT_AVAILABLE.
         */
        fun makeNew(raw: Map<String, Boolean?>): Result<ShopFeatures, InputProblems> {
            val problems = raw.keys.filter { it !in KEYS }.map { Failure.Unknown.under(it) } +
                KEYS.filter { raw[it] == null }.map { FieldRequired.under(it) } +
                listOfNotNull(if (raw[GIFT_WRAP] == true) Failure.NotAvailable.under(GIFT_WRAP) else null)
            if (problems.isNotEmpty()) return Result.failure(InputProblems(problems))
            return Result.success(ShopFeatures(raw.getValue(WISHLIST_SHARE)!!, raw.getValue(REVIEW_MEDIA)!!, giftWrap = false))
        }

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(wishlistShare: Boolean, reviewMedia: Boolean, giftWrap: Boolean): ShopFeatures =
            ShopFeatures(wishlistShare, reviewMedia, giftWrap)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Unknown : Failure("FEATURE_UNKNOWN")

        data object NotAvailable : Failure("FEATURE_NOT_AVAILABLE")
    }
}
