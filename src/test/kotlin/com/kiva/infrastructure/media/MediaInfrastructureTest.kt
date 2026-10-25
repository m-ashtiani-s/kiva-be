package com.kiva.infrastructure.media

import com.kiva.infrastructure.httpserver.media.copyToFileWithin
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pieces of the uploads that need no tools and no database: the disk, the capped copy, and the blurhash. */
class MediaInfrastructureTest {

    @TempDir
    lateinit var root: Path

    // --- the disk ---

    @Test
    fun `a work folder is moved into place whole, and removed again`() {
        val storage = DiskMediaStorage(root)
        val work = storage.newWorkDir()
        Files.write(work.resolve("w160.webp"), byteArrayOf(1, 2, 3))

        storage.commit(work, "2036/03/med_5Hq2xT9aLmQ0vB3nR7sK1w")

        val stored = root.resolve("2036/03/med_5Hq2xT9aLmQ0vB3nR7sK1w/w160.webp")
        assertTrue(Files.exists(stored))
        assertFalse(Files.exists(work))
        storage.delete("2036/03/med_5Hq2xT9aLmQ0vB3nR7sK1w")
        assertFalse(Files.exists(stored.parent))
        storage.delete("2036/03/med_5Hq2xT9aLmQ0vB3nR7sK1w")
    }

    @Test
    fun `a path that would leave the media root is refused, not followed`() {
        val storage = DiskMediaStorage(root)

        listOf("../outside", "2036/../../outside", "/etc", "..", ".tmp").forEach {
            assertFailsWith<IllegalArgumentException>(it) { storage.delete(it) }
        }
        assertFailsWith<IllegalArgumentException> { storage.discard(root.resolve("2036")) }
    }

    @Test
    fun `uploads and work folders are kept in tmp, on the media's own disk`() {
        val storage = DiskMediaStorage(root)

        assertTrue(storage.newUploadFile().startsWith(root.toAbsolutePath().resolve(".tmp")))
        assertTrue(storage.newWorkDir().startsWith(root.toAbsolutePath().resolve(".tmp")))
    }

    // --- the capped copy ---

    /** A channel that sends [total] bytes in chunks and counts how many it was asked for. */
    private fun CoroutineScope.sending(total: Long, sent: AtomicLong): ByteReadChannel = writer {
        val chunk = ByteArray(16 * 1024)
        try {
            while (sent.get() < total) {
                channel.writeFully(chunk)
                sent.addAndGet(chunk.size.toLong())
                channel.flush()
            }
        } catch (_: Exception) {
            // The reader stopped reading: the sender stops too.
        }
    }.channel

    @Test
    fun `a file within the cap is copied whole`() = runBlocking<Unit> {
        val file = root.resolve("upload.bin")

        val copied = ByteReadChannel(ByteArray(1000) { it.toByte() }).copyToFileWithin(file, maxBytes = 1000)

        assertEquals(1000L, copied)
        assertEquals(1000L, Files.size(file))
    }

    @Test
    fun `a file over the cap is cut off the moment it passes it - never read whole`() = runBlocking<Unit> {
        val sent = AtomicLong()
        val file = root.resolve("upload.bin")

        val copied = sending(total = 100L * 1024 * 1024, sent = sent).copyToFileWithin(file, maxBytes = 1024 * 1024)

        assertNull(copied)
        assertTrue(sent.get() < 4L * 1024 * 1024, "read ${sent.get()} bytes of 100 MB")
        assertTrue(Files.size(file) <= 1024 * 1024)
    }

    // --- the blurhash ---

    private fun solid(rgb: Int, width: Int = 32, height: Int = 24) =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { for (x in 0 until width) for (y in 0 until height) setRGB(x, y, rgb) }

    @Test
    fun `a blurhash of 4 by 3 is 28 characters and starts with its size flag`() {
        val hash = Blurhash.encode(solid(0xC8A2C8))

        assertEquals(28, hash.length)
        assertEquals('L', hash.first())
    }

    @Test
    fun `a solid color's average is the color itself`() {
        // The average is characters 2 to 5: the sRGB color as one number in base 83.
        assertEquals("00", Blurhash.encode(solid(0x000000)).substring(2, 4))
        assertEquals(Blurhash.encode(solid(0xFF0000)).substring(2, 6), base83(0xFF0000, 4))
        assertEquals(Blurhash.encode(solid(0xC8A2C8)).substring(2, 6), base83(0xC8A2C8, 4))
    }

    @Test
    fun `the same picture always gives the same hash, and a different one another`() {
        val gradient = BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 32) for (y in 0 until 24) setRGB(x, y, (x * 8 shl 16) or (y * 10 shl 8) or 0x80)
        }

        assertEquals(Blurhash.encode(gradient), Blurhash.encode(gradient))
        assertTrue(Blurhash.encode(gradient) != Blurhash.encode(solid(0x808080)))
        assertEquals(GRADIENT_HASH, Blurhash.encode(gradient))
    }

    private fun base83(value: Int, length: Int): String {
        val characters = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
        var rest = value
        return (1..length).map { characters[rest % 83].also { rest /= 83 } }.reversed().joinToString("")
    }

    private companion object {
        /** Pinned from this implementation: a change of the encoding changes it. */
        const val GRADIENT_HASH = "LxH27k2swxX8mHWWjtf7gJfjfQfj"
    }
}

