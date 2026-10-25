package com.kiva.infrastructure.httpserver.media

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Copies an upload to [file] as it arrives, and stops reading the moment it passes [maxBytes]: a file over the cap is
 * never read whole, nor kept (roadmap section 3, row 3). Returns how many bytes were copied, or null when the cap was
 * passed.
 */
suspend fun ByteReadChannel.copyToFileWithin(file: Path, maxBytes: Long): Long? = withContext(Dispatchers.IO) {
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    Files.newOutputStream(file).use { out ->
        while (true) {
            val read = readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > maxBytes) {
                // Tell the sender no more is read: it stops at once instead of waiting for room that never comes.
                cancel(null)
                return@withContext null
            }
            out.write(buffer, 0, read)
        }
    }
    total
}

private const val BUFFER_BYTES = 64 * 1024
