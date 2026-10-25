package com.kiva.infrastructure.media

/**
 * Public addresses of stored files: `{publicBaseUrl}/{relative path}`. Only relative paths are stored, so this is the
 * one place that knows where the files are served from (nginx and the CDN on the server, the app itself in development).
 */
class MediaUrls(private val publicBaseUrl: String) {
    fun of(path: String): String = "$publicBaseUrl/$path"
}
