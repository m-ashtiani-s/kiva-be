package com.kiva.domain.aggregate.media.model.valueobject

/**
 * One stored size of a file: its [width] in pixels and its [path] relative to the media root
 * (`2026/10/med_…/w800.webp`). Only relative paths are kept, so the public address can move by configuration alone.
 */
@ConsistentCopyVisibility
data class MediaVariant private constructor(val width: Int, val path: String) {

    init {
        require(width > 0) { "Invalid MediaVariant width $width" }
        require(isRelativePath(path)) { "Invalid MediaVariant path '$path'" }
    }

    companion object {
        /** Made by the server only (the processor names the files), never from a client. */
        fun of(width: Int, path: String): MediaVariant = MediaVariant(width, path)

        /** A path inside the media root: no leading slash, no `..`, no backslash, nothing but plain names. */
        fun isRelativePath(path: String): Boolean =
            path.isNotEmpty() && path.length <= 200 && !path.startsWith('/') && path.split('/').all { it.matches(SEGMENT) }

        private val SEGMENT = Regex("^[0-9A-Za-z_][0-9A-Za-z_.-]*$")
    }
}
