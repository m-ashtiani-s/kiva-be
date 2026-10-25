package com.kiva.application.usecase.banner

import com.kiva.application.dto.BannerDto
import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.usecase.product.MomentField
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.banner.entity.Banner
import com.kiva.domain.aggregate.banner.model.BannerDraft
import com.kiva.domain.aggregate.media.repository.MediaRepository

/**
 * The contract's BannerUpsertRequest as it arrived, every field raw and nullable: the same form for `POST` and `PUT
 * /admin/banners*`. `PUT` replaces the whole banner, so a field left out is its default (`theme` CREAM, `sortOrder` 0).
 */
data class BannerFields(
    val placement: String?,
    val theme: String?,
    val tagLabel: String?,
    val tagIcon: String?,
    val title: String?,
    val text: String?,
    val ctaLabel: String?,
    val ctaUrl: String?,
    val imageMediaId: String?,
    val overlayHighlight: String?,
    val overlayTitle: String?,
    val overlaySubtitle: String?,
    val overlayChannel: String?,
    val sortOrder: Int?,
    val startsAt: String?,
    val endsAt: String?,
) {
    /** Every problem of the form at once, each under its field. */
    fun check(): Result<BannerDraft, InputProblems> = BannerDraft.makeNew(
        BannerDraft.Input(
            placement, theme, tagLabel, tagIcon, title, text, ctaLabel, ctaUrl, imageMediaId, overlayHighlight, overlayTitle, overlaySubtitle,
            overlayChannel, sortOrder, MomentField.makeNew(startsAt), MomentField.makeNew(endsAt),
        ),
    )
}

/**
 * Banners with their pictures in full (Rule 15): the shop's list, the home page and the panel all show a banner the
 * same way. One read of the pictures for any number of banners; a banner's picture, once attached, is never removed.
 * Inside the caller's transaction.
 */
class Banners(private val mediaRepository: MediaRepository) {

    fun of(banners: List<Banner>): List<BannerDto> {
        if (banners.isEmpty()) return emptyList()
        val images = mediaRepository.findAll(banners.map { it.imageMediaId }.distinct()).associateBy { it.id }
        return banners.map { banner ->
            val image = images[banner.imageMediaId] ?: error("Banner ${banner.id.value} points at media ${banner.imageMediaId.value}, which is gone")
            BannerDto(banner, UploadedMediaDto.from(image))
        }
    }
}
