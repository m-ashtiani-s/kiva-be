package com.kiva.domain.aggregate.media.model.valueobject

/** Photo or video. A color with a video gets the «ویدیو» badge and passes the `hasVideo` filter. */
enum class MediaType {
    IMAGE,
    VIDEO,
}
