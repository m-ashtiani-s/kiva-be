package com.kiva.application.dto

import com.kiva.domain.aggregate.banner.entity.Banner

/** A banner and its picture in full (contract Banner). */
data class BannerDto(val banner: Banner, val image: UploadedMediaDto) {
    override fun toString(): String = "BannerDto(id=${banner.id.value})"
}
