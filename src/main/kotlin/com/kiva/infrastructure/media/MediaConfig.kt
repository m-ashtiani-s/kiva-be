package com.kiva.infrastructure.media

import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where uploaded files live and how they are reached (`kiva.media`, roadmap section 23).
 *
 * @property root the folder of the processed files; on the server `/var/lib/kiva/media` (owner kiva, 750).
 * @property publicBaseUrl the public address of [root], without a `/` at the end: a stored file is
 *   `{publicBaseUrl}/{its relative path}`. Only relative paths are stored, so this can change later.
 * @property serveLocally the app serves `/media` itself: development only; on the server nginx does.
 * @property vipsBin, ffmpegBin the tools, a name on the PATH or a full path (`C:\…\vips.exe` on a Windows machine).
 *   Their siblings vipsheader and ffprobe are looked for next to them.
 */
data class MediaConfig(
    val root: Path,
    val publicBaseUrl: String,
    val serveLocally: Boolean,
    val vipsBin: String,
    val ffmpegBin: String,
) {
    init {
        require(PUBLIC_URL.matches(publicBaseUrl) && runCatching { URI(publicBaseUrl) }.isSuccess) {
            "kiva.media.publicBaseUrl (KIVA_MEDIA_PUBLIC_URL) must be a full http(s) address without a / at the end, was '$publicBaseUrl'"
        }
        require(vipsBin.isNotBlank()) { "kiva.media.vipsBin (KIVA_VIPS_BIN) is empty" }
        require(ffmpegBin.isNotBlank()) { "kiva.media.ffmpegBin (KIVA_FFMPEG_BIN) is empty" }
    }

    /** vipsheader, next to [vipsBin]. */
    val vipsHeaderBin: String get() = sibling(vipsBin, "vips", "vipsheader")

    /** ffprobe, next to [ffmpegBin]. */
    val ffprobeBin: String get() = sibling(ffmpegBin, "ffmpeg", "ffprobe")

    companion object {
        private val PUBLIC_URL = Regex("^https?://[^\\s]+[^/\\s]$")

        /** Development defaults: `build/media`, served by the app itself, tools on the PATH. */
        val DEFAULT = MediaConfig(Paths.get("build/media"), "http://localhost:8080/media", serveLocally = true, vipsBin = "vips", ffmpegBin = "ffmpeg")

        /** `C:\vips\bin\vips.exe` → `C:\vips\bin\vipsheader.exe`; `vips` → `vipsheader`. */
        private fun sibling(bin: String, name: String, siblingName: String): String {
            val path = Paths.get(bin)
            val file = path.fileName.toString()
            val renamed = if (file.startsWith(name)) siblingName + file.removePrefix(name) else siblingName
            return path.parent?.resolve(renamed)?.toString() ?: renamed
        }
    }
}
