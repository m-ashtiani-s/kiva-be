package com.kiva.domain.aggregate.banner.entity

import com.kiva.common.valueobject.OpenWindow
import com.kiva.domain.aggregate.banner.model.BannerDraft
import com.kiva.domain.aggregate.banner.model.valueobject.BannerCta
import com.kiva.domain.aggregate.banner.model.valueobject.BannerId
import com.kiva.domain.aggregate.banner.model.valueobject.BannerOverlay
import com.kiva.domain.aggregate.banner.model.valueobject.BannerPlacement
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTag
import com.kiva.domain.aggregate.banner.model.valueobject.BannerTheme
import com.kiva.domain.aggregate.media.model.valueobject.MediaId
import java.time.Instant

/**
 * A banner of one placement (roadmap phase 11, the contract's Banner). Kept as a row; the panel makes, replaces and
 * deletes it. It has no switch (decision 7): it shows while [window] contains the moment; to hide it, end its window or
 * delete it.
 *
 * Rules it keeps itself: a picture always, a button with both its parts. What it can't know: whether its picture is a
 * BANNER image (the use case, MediaAttachments).
 */
class Banner private constructor(
    val id: BannerId,
    val placement: BannerPlacement,
    val theme: BannerTheme,
    val tag: BannerTag?,
    val title: String,
    val text: String?,
    val cta: BannerCta?,
    val imageMediaId: MediaId,
    val overlay: BannerOverlay?,
    val sortOrder: Int,
    val window: OpenWindow,
    val createdOn: Instant,
    val updatedOn: Instant,
) {
    init {
        require(title.isNotBlank()) { "Banner ${id.value} has no title" }
    }

    fun isVisibleAt(now: Instant): Boolean = window.contains(now)

    /** The banner as [draft] makes it, or null when [draft] would leave it as it is (no save, no change log row). */
    fun replacedBy(draft: BannerDraft, now: Instant): Banner? {
        val same = draft.placement == placement && draft.theme == theme && draft.tag == tag && draft.title == title && draft.text == text &&
            draft.cta == cta && draft.imageMediaId == imageMediaId && draft.overlay == overlay && draft.sortOrder == sortOrder && draft.window == window
        if (same) return null
        return of(id, draft, createdOn, now)
    }

    override fun toString(): String = "Banner(${id.value})"

    companion object {
        fun create(id: BannerId, draft: BannerDraft, now: Instant): Banner = of(id, draft, now, now)

        private fun of(id: BannerId, draft: BannerDraft, createdOn: Instant, updatedOn: Instant) = Banner(
            id, draft.placement, draft.theme, draft.tag, draft.title, draft.text, draft.cta, draft.imageMediaId, draft.overlay, draft.sortOrder,
            draft.window, createdOn, updatedOn,
        )

        /** For values read back from storage. Still checked by `init`. */
        fun constructFromPersisted(
            id: BannerId,
            placement: BannerPlacement,
            theme: BannerTheme,
            tag: BannerTag?,
            title: String,
            text: String?,
            cta: BannerCta?,
            imageMediaId: MediaId,
            overlay: BannerOverlay?,
            sortOrder: Int,
            window: OpenWindow,
            createdOn: Instant,
            updatedOn: Instant,
        ): Banner = Banner(id, placement, theme, tag, title, text, cta, imageMediaId, overlay, sortOrder, window, createdOn, updatedOn)
    }
}
