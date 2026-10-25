package com.kiva.infrastructure.media

import com.kiva.application.port.MediaStorage
import com.kiva.common.logging.getLogger
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Keeps processed files on this server's disk, under [root] (roadmap section 5.2):
 *
 * ```
 * {root}/{yyyy}/{mm}/{mediaId}/w160.webp, …, video.mp4, poster-w480.webp, poster-w800.webp
 * {root}/.tmp/…   uploads being read and files being processed
 * ```
 *
 * A file's folder is written whole in `.tmp` and moved into place in one atomic step, so a reader never sees half of
 * it. Folders are only ever added or removed, never changed: nginx can tell browsers and the CDN to keep them for ever.
 * Every path given is checked to stay inside [root].
 */
class DiskMediaStorage(private val root: Path) : MediaStorage {

    private val logger = getLogger()
    private val absoluteRoot: Path = root.toAbsolutePath().normalize()

    /** Uploads being read and files being processed: on the same disk as the media, so a move is atomic. */
    val tmp: Path get() = absoluteRoot.resolve(TMP)

    override fun newWorkDir(): Path {
        Files.createDirectories(tmp)
        return Files.createDirectory(tmp.resolve("work-" + UUID.randomUUID()))
    }

    /** A new empty temporary file for an upload being read; the route removes it when the request is done. */
    fun newUploadFile(): Path {
        Files.createDirectories(tmp)
        return Files.createTempFile(tmp, "upload-", ".bin")
    }

    override fun commit(workDir: Path, storageDir: String) {
        val target = inside(storageDir)
        Files.createDirectories(target.parent)
        Files.move(workDir, target, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun discard(workDir: Path) {
        val dir = workDir.toAbsolutePath().normalize()
        require(dir.startsWith(tmp)) { "Not a work folder of the media: $workDir" }
        removeTree(dir)
    }

    override fun delete(storageDir: String) {
        removeTree(inside(storageDir))
    }

    /** [relative] under [root]; a path that would leave it (`..`, absolute) is a bug, not something to follow. */
    fun inside(relative: String): Path {
        require(MediaVariant.isRelativePath(relative)) { "Not a media path: '$relative'" }
        val path = absoluteRoot.resolve(relative).normalize()
        require(path.startsWith(absoluteRoot) && path != absoluteRoot) { "Not a media path: '$relative'" }
        return path
    }

    private fun removeTree(dir: Path) {
        if (!Files.exists(dir)) return
        try {
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        } catch (e: java.io.IOException) {
            logger.warn("DiskMediaStorage: could not remove ${absoluteRoot.relativize(dir)}; it stays on the disk", e)
        }
    }

    private companion object {
        const val TMP = ".tmp"
    }
}
