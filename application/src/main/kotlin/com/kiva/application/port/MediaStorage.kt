package com.kiva.application.port

import java.nio.file.Path

/**
 * Where processed files live (roadmap section 5.2): a folder per file under the media root, today on this server's
 * disk (DiskMediaStorage); object storage would be another adapter. Names are always the server's, never a client's,
 * and a stored file is never changed, so it can be cached for ever.
 */
interface MediaStorage {

    /** A new empty folder to process into, on the same disk as the media, so [commit] is one atomic move. */
    fun newWorkDir(): Path

    /** Moves [workDir] to [storageDir] (relative to the media root) in one atomic step: the files appear all at once. */
    fun commit(workDir: Path, storageDir: String)

    /** Removes [workDir] and whatever is in it; nothing when it is already gone. */
    fun discard(workDir: Path)

    /** Removes a stored file's folder; nothing when it is already gone. */
    fun delete(storageDir: String)
}
