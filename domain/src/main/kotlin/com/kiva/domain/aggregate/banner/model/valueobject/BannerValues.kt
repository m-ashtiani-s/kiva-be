package com.kiva.domain.aggregate.banner.model.valueobject

import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.valueobject.SiteLink
import com.kiva.domain.aggregate.settings.model.valueobject.IconName
import com.kiva.domain.aggregate.settings.model.valueobject.SocialChannel

/** The id of a banner (its row's), given out by the repository when the panel makes one. */
@JvmInline
value class BannerId private constructor(val value: Long) {

    init {
        require(value > 0) { "Invalid BannerId $value" }
    }

    companion object {
        /** An id as a path sent it; the panel answers a malformed one as it answers one nobody has (404). */
        fun makeNew(raw: Long): Result<BannerId, Failure> = if (raw > 0) Result.success(BannerId(raw)) else Result.failure(Failure.Invalid)

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(value: Long): BannerId = BannerId(value)
    }

    sealed class Failure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Invalid : Failure("BANNER_ID_INVALID")
    }
}

/** Where a banner shows (the contract's BannerPlacement). */
enum class BannerPlacement {
    HOME_HERO,
    HOME_PROMO,
    SHOP_TOP,
    PRODUCT_SIDEBAR,
    BLOG_TOP,
}

/** A banner's colours (the contract's `theme`). */
enum class BannerTheme {
    CREAM,
    PURPLE,
    LILAC,
    DARK,
    WHITE,
}

/** The small tag over a banner's title («رزرو ۴ روزه»), with an icon or not. */
data class BannerTag(val label: String, val icon: IconName?)

/** A banner's button: both its label and where it goes, always (decision 8). */
data class BannerCta(val label: String, val url: SiteLink)

/** The label over a banner's picture («۴ روز رزرو»); at least one part, or there is no overlay. */
data class BannerOverlay(val highlight: String?, val title: String?, val subtitle: String?, val channel: SocialChannel?) {
    init {
        require(highlight != null || title != null || subtitle != null || channel != null) { "An empty overlay is no overlay" }
    }
}
