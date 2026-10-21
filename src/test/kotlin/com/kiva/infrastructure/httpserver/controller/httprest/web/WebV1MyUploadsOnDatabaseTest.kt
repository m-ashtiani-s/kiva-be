package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.ProcessedMedia
import com.kiva.application.usecase.media.MediaUploader
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.domain.aggregate.media.model.valueobject.MediaProbe
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
import com.kiva.infrastructure.media.MediaConfig
import com.kiva.infrastructure.media.MediaTools
import com.kiva.infrastructure.media.RandomMediaIds
import com.kiva.infrastructure.media.VipsFfmpegMediaProcessor
import com.kiva.infrastructure.persistence.exposed.tbl.AuditLogTbl
import com.kiva.infrastructure.persistence.exposed.tbl.MediaTbl
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /me/uploads` over kiva_test (roadmap step 7.1): the panel's upload, the customer's way. The same stack as the
 * panel's (PanelTestStack) with its fake processor, and one test with the real tools when they are installed.
 */
class WebV1MyUploadsOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-03-06T09:00:00Z"))
    private var stack = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteWhatWasStored()

    private val mb = 1024 * 1024

    private suspend fun ApplicationTestBuilder.upload(
        caller: PanelTestStack.Caller,
        purpose: String? = "RETURN_EVIDENCE",
        bytes: ByteArray = FakeMediaProcessor.photo(1200, 900),
        fileName: String = "IMG_2041.jpg",
    ): HttpResponse = client.post("$API_V1/me/uploads") {
        header(HttpHeaders.Authorization, caller.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    purpose?.let { append("purpose", it) }
                    append(
                        "file", bytes,
                        Headers.build {
                            append(HttpHeaders.ContentType, "image/jpeg")
                            append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                        },
                    )
                },
            ),
        )
    }

    private fun HttpResponse.json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun rowsOf(caller: PanelTestStack.Caller): Long = transaction(stack.db) { MediaTbl.selectAll().where { MediaTbl.uploadedBy eq caller.userId }.count() }

    @Test
    fun `a customer's photo of 7 MB is 201 with its MediaAsset, stored for its purpose, by them, attached to nothing`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)

        val response = upload(customer, bytes = FakeMediaProcessor.photo(1200, 900) + ByteArray(7 * mb))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val id = response.json(response.bodyAsText()).getValue("id").jsonPrimitive.content
        val row = transaction(stack.db) { MediaTbl.selectAll().where { MediaTbl.mediaId eq id }.single() }
        assertEquals("RETURN_EVIDENCE", row[MediaTbl.purpose])
        assertEquals(customer.userId, row[MediaTbl.uploadedBy])
        assertEquals(null, row[MediaTbl.attachedOn])
        assertEquals(null, row[MediaTbl.alt])
    }

    @Test
    fun `a photo of 9 MB is 413 with the customer's 8 MB cap - 60 MB stops the reading at 50 MB - nothing is kept`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)

        val photo = upload(customer, bytes = FakeMediaProcessor.photo(1200, 900) + ByteArray(9 * mb))
        val huge = upload(customer, bytes = FakeMediaProcessor.photo(1200, 900) + ByteArray(60 * mb))

        assertEquals(HttpStatusCode.PayloadTooLarge, photo.status)
        assertEquals(8L * mb, photo.json(photo.bodyAsText()).getValue("meta").jsonObject.getValue("maxBytes").jsonPrimitive.content.toLong())
        assertEquals(HttpStatusCode.PayloadTooLarge, huge.status)
        assertEquals(50L * mb, huge.json(huge.bodyAsText()).getValue("meta").jsonObject.getValue("maxBytes").jsonPrimitive.content.toLong())
        assertEquals(0, rowsOf(customer))
    }

    @Test
    fun `a video of 40 MB and 50 seconds is 201 - one of 70 seconds is 400 VIDEO_TOO_LONG on file`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)

        val fine = upload(customer, bytes = FakeMediaProcessor.video(seconds = 50, bytes = 40 * mb), fileName = "clip.mp4")
        val long = upload(customer, bytes = FakeMediaProcessor.video(seconds = 70), fileName = "clip.mp4")

        assertEquals(HttpStatusCode.Created, fine.status, fine.bodyAsText())
        assertEquals("VIDEO", fine.json(fine.bodyAsText()).getValue("type").jsonPrimitive.content)
        assertEquals(HttpStatusCode.BadRequest, long.status)
        assertTrue("VIDEO_TOO_LONG" in long.bodyAsText() && "\"file\"" in long.bodyAsText(), long.bodyAsText())
    }

    @Test
    fun `a staff purpose from a customer is 400 MEDIA_PURPOSE_INVALID - no token is 401`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)

        val product = upload(customer, purpose = "PRODUCT")
        val none = upload(customer, purpose = null)

        assertEquals(HttpStatusCode.BadRequest, product.status)
        assertTrue("MEDIA_PURPOSE_INVALID" in product.bodyAsText(), product.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, none.status)
        assertTrue("FIELD_REQUIRED" in none.bodyAsText(), none.bodyAsText())
        assertEquals(HttpStatusCode.Unauthorized, client.post("$API_V1/me/uploads").status)
        assertEquals(0, rowsOf(customer))
    }

    @Test
    fun `the 21st upload of the hour is 429 RATE_LIMITED with Retry-After - an hour after the first, one more fits`() = stack.app {
        val customer = stack.caller(PanelTestStack.phone(80))
        repeat(20) {
            assertEquals(HttpStatusCode.Created, upload(customer).status, "upload ${it + 1}")
            clock.advance(Duration.ofSeconds(30))
        }

        val refused = upload(customer)

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        // The first was 10 minutes ago: 50 minutes to wait.
        assertEquals("3000", refused.headers[HttpHeaders.RetryAfter])
        assertEquals("3000", refused.json(refused.bodyAsText()).getValue("meta").jsonObject.getValue("retryAfterSeconds").jsonPrimitive.content)
        assertEquals(20, rowsOf(customer))

        clock.advance(Duration.ofMinutes(49))
        // Logged in again: the access token lives 15 minutes.
        assertEquals(HttpStatusCode.TooManyRequests, upload(stack.caller(PanelTestStack.phone(80))).status)
        clock.advance(Duration.ofMinutes(1))
        assertEquals(HttpStatusCode.Created, upload(stack.caller(PanelTestStack.phone(80))).status)
    }

    @Test
    fun `another customer's uploads and staff's don't count against a customer`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)
        val other = stack.caller(PanelTestStack.phone(90))
        repeat(20) { assertEquals(HttpStatusCode.Created, upload(other).status) }

        assertEquals(HttpStatusCode.Created, upload(customer).status)
    }

    @Test
    fun `25 uploads of one customer at the same moment leave exactly 20 rows - the rest are 429 and keep no folder`() {
        val customer = stack.callerWith(Role.CUSTOMER)
        // Every upload is past the first count and its processing, and about to take the lock of the customer's uploads.
        val allProcessed = CyclicBarrier(25)
        val processor = object : MediaProcessor by FakeMediaProcessor() {
            override suspend fun process(file: Path, probe: MediaProbe, into: Path): ProcessedMedia {
                val processed = FakeMediaProcessor().process(file, probe, into)
                allProcessed.await(10, TimeUnit.SECONDS)
                return processed
            }
        }
        val uploadMedia = UploadMediaUc(stack.transactions, processor, stack.mediaStorage, stack.mediaRepository, RandomMediaIds(), clock)
        val earlier = List(5) { stack.mediaStorage.newUploadFile().also { Files.write(it, FakeMediaProcessor.photo(800, 600)) } }
        earlier.forEach { file ->
            val done = runBlocking { stack.uploadMedia.execute(UploadMediaUc.Cmd(listOf(UploadMediaUc.UploadedFile(file, Files.size(file))), "RETURN_EVIDENCE", null, MediaUploader.Customer(customer.user.id))) }
            assertTrue(done.isSuccess)
        }

        val pool = Executors.newFixedThreadPool(25)
        val outcomes = try {
            (1..25).map {
                val file = stack.mediaStorage.newUploadFile().also { Files.write(it, FakeMediaProcessor.photo(800, 600)) }
                pool.submit<String> {
                    runBlocking {
                        val command = UploadMediaUc.Cmd(listOf(UploadMediaUc.UploadedFile(file, Files.size(file))), "RETURN_EVIDENCE", null, MediaUploader.Customer(customer.user.id))
                        uploadMedia.execute(command).failureOrNull()?.code ?: "STORED"
                    }
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(15, outcomes.count { it == "STORED" }, outcomes.toString())
        assertEquals(10, outcomes.count { it == "RATE_LIMITED" }, outcomes.toString())
        assertEquals(20, rowsOf(customer))
        val folders = Files.walk(stack.mediaRoot).use { paths -> paths.filter { Files.isDirectory(it) && it.fileName.toString().startsWith("med_") }.count() }
        assertEquals(20, folders, "a refused upload keeps no folder")
    }

    @Test
    fun `a customer's upload writes nothing to the change log, and the client's file name is nowhere`() = stack.app {
        val customer = stack.callerWith(Role.CUSTOMER)

        val response = upload(customer, fileName = "../../secret-name.jpg")

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(0, transaction(stack.db) { AuditLogTbl.selectAll().where { AuditLogTbl.actorId eq customer.userId }.count() })
        val stored = Files.walk(stack.mediaRoot).use { paths -> paths.map { it.toString() }.toList() }
        assertTrue(stored.none { "secret-name" in it }, stored.toString())
        val row = transaction(stack.db) { MediaTbl.selectAll().where { MediaTbl.uploadedBy eq customer.userId }.single() }
        assertFalse(MediaTbl.columns.any { "secret-name" in row[it].toString() })
    }

    @Test
    fun `with the real tools, a customer's photo with GPS is stored without any metadata`() {
        val config = MediaConfig.DEFAULT.copy(vipsBin = System.getenv("KIVA_VIPS_BIN") ?: "vips", ffmpegBin = System.getenv("KIVA_FFMPEG_BIN") ?: "ffmpeg")
        assumeTrue(MediaTools.available(config), "libvips and ffmpeg are not installed here (kiva.media.vipsBin / ffmpegBin); the real-tool test is skipped")
        stack.deleteWhatWasStored()
        stack = PanelTestStack(clock, VipsFfmpegMediaProcessor(config))
        stack.app {
            val response = upload(stack.callerWith(Role.CUSTOMER), bytes = FakeMediaProcessor.photoWithGps(1200, 900))

            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val webps = Files.walk(stack.mediaRoot).use { paths -> paths.filter { it.toString().endsWith(".webp") }.toList() }
            assertTrue(webps.isNotEmpty())
            webps.forEach { file ->
                val process = ProcessBuilder(config.vipsHeaderBin, "-a", file.toString()).redirectErrorStream(true).start()
                val header = ByteArrayOutputStream().also { process.inputStream.copyTo(it) }.toString(Charsets.UTF_8).also { process.waitFor() }.lowercase()
                assertFalse("exif" in header || "gps" in header, "$file keeps metadata: $header")
            }
        }
    }
}
