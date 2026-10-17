package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.domain.aggregate.media.model.valueobject.VideoCodec
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.MutableClock
import com.kiva.infrastructure.httpserver.API_V1
import com.kiva.infrastructure.media.FakeMediaProcessor
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /admin/media` over kiva_test with the panel's real stack, the files on a temporary media root, and a fake
 * processor (FakeMediaProcessor) in place of libvips and ffmpeg; the real tools are tested on their own
 * (VipsFfmpegMediaProcessorTest).
 */
class WebV1AdminMediaOnDatabaseTest {

    private val clock = MutableClock(Instant.parse("2036-03-06T09:00:00Z"))
    private val stack = PanelTestStack(clock)

    @AfterEach
    fun deleteWhatWasStored() = stack.deleteWhatWasStored()

    private class Part(val name: String, val bytes: ByteArray, val fileName: String = "photo.jpg")

    private suspend fun ApplicationTestBuilder.upload(
        caller: PanelTestStack.Caller,
        purpose: String? = "PRODUCT",
        alt: String? = null,
        files: List<Part> = listOf(Part("file", FakeMediaProcessor.photo(4000, 3000))),
        purposeFirst: Boolean = true,
    ): HttpResponse = client.post("$API_V1/admin/media") {
        header(HttpHeaders.Authorization, caller.bearer)
        setBody(
            MultiPartFormDataContent(
                formData {
                    if (purposeFirst) purpose?.let { append("purpose", it) }
                    alt?.let { append("alt", it) }
                    files.forEach { part ->
                        append(
                            part.name, part.bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, "image/jpeg")
                                append(HttpHeaders.ContentDisposition, "filename=\"${part.fileName}\"")
                            },
                        )
                    }
                    if (!purposeFirst) purpose?.let { append("purpose", it) }
                },
            ),
        )
    }

    private fun HttpResponse.json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun storedFiles(): List<String> =
        Files.walk(stack.mediaRoot).use { paths -> paths.filter { Files.isRegularFile(it) }.map { stack.mediaRoot.relativize(it).toString().replace('\\', '/') }.toList() }

    @Test
    fun `a photo is 201 with every field of MediaAsset, its five sizes on the disk and its row attached to nothing`() = stack.app {
        val manager = stack.callerWith(Role.CATALOG_MANAGER)

        val response = upload(manager, alt = "کیف یاسی — نمای جلو")

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.json(response.bodyAsText())
        val id = body.getValue("id").jsonPrimitive.content
        assertTrue(id.matches(Regex("^med_[0-9A-Za-z]{22}$")), id)
        val base = "${PanelTestStack.MEDIA_BASE_URL}/2036/03/$id"
        assertEquals("IMAGE", body.getValue("type").jsonPrimitive.content)
        assertEquals("$base/w1600.webp", body.getValue("url").jsonPrimitive.content)
        assertEquals("$base/w160.webp", body.getValue("thumbnailUrl").jsonPrimitive.content)
        assertEquals(listOf(160, 480, 800, 1200, 1600), body.getValue("srcSet").jsonArray.map { it.jsonObject.getValue("width").jsonPrimitive.int })
        assertEquals(1600 to 1200, body.getValue("width").jsonPrimitive.int to body.getValue("height").jsonPrimitive.int)
        assertEquals("image/webp", body.getValue("mimeType").jsonPrimitive.content)
        assertEquals("کیف یاسی — نمای جلو", body.getValue("alt").jsonPrimitive.content)
        assertEquals(JsonNull, body.getValue("posterUrl"))
        assertEquals(JsonNull, body.getValue("durationSec"))
        assertEquals(
            setOf("id", "type", "url", "thumbnailUrl", "srcSet", "posterUrl", "width", "height", "durationSec", "mimeType", "alt", "blurhash"),
            body.keys,
        )
        assertEquals(listOf(160, 480, 800, 1200, 1600).map { "2036/03/$id/w$it.webp" }.toSet(), storedFiles().toSet())
        val row = transaction(stack.db) { MediaTbl.selectAll().where { MediaTbl.mediaId eq id }.single() }
        assertEquals("PRODUCT", row[MediaTbl.purpose])
        assertEquals(manager.userId, row[MediaTbl.uploadedBy])
        assertEquals(null, row[MediaTbl.attachedOn])
    }

    @Test
    fun `a video for a product is 201 with its covers and length`() = stack.app {
        val response = upload(stack.callerWith(Role.ADMIN), files = listOf(Part("file", FakeMediaProcessor.video(), "clip.mp4")))

        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val body = response.json(response.bodyAsText())
        assertEquals("VIDEO", body.getValue("type").jsonPrimitive.content)
        assertTrue(body.getValue("url").jsonPrimitive.content.endsWith("/video.mp4"))
        assertTrue(body.getValue("posterUrl").jsonPrimitive.content.endsWith("/poster-w800.webp"))
        assertTrue(body.getValue("thumbnailUrl").jsonPrimitive.content.endsWith("/poster-w480.webp"))
        assertEquals(24, body.getValue("durationSec").jsonPrimitive.int)
        assertEquals("video/mp4", body.getValue("mimeType").jsonPrimitive.content)
    }

    @Test
    fun `an HEVC video is 415 with the hint H264 - a GIF, an SVG or a program named jpg too`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)
        val hevc = upload(admin, files = listOf(Part("file", FakeMediaProcessor.video(codec = VideoCodec.HEVC), "IMG_0001.mov")))
        assertEquals(HttpStatusCode.UnsupportedMediaType, hevc.status)
        assertEquals("H264", hevc.json(hevc.bodyAsText()).getValue("meta").jsonObject.getValue("hint").jsonPrimitive.content)

        val gif = FakeMediaProcessor.photo(400, 400, "gif")
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"><rect width="400" height="400"/></svg>""".toByteArray()
        val program = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00) + ByteArray(1000)
        listOf(gif to "a.gif", svg to "a.svg", program to "photo.jpg").forEach { (bytes, name) ->
            val response = upload(admin, files = listOf(Part("file", bytes, name)))
            assertEquals(HttpStatusCode.UnsupportedMediaType, response.status, name)
            assertTrue("UPLOAD_TYPE_NOT_ALLOWED" in response.bodyAsText(), name)
        }
        assertEquals(emptyList(), storedFiles().filterNot { it.startsWith(".tmp") })
    }

    @Test
    fun `a video for a category is 415 - its purpose takes photos only`() = stack.app {
        val response = upload(stack.callerWith(Role.ADMIN), purpose = "CATEGORY", files = listOf(Part("file", FakeMediaProcessor.video(), "clip.mp4")))

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertTrue("image/jpeg" in response.bodyAsText() && "video/mp4" !in response.bodyAsText(), response.bodyAsText())
    }

    @Test
    fun `16 MB for a category is 413 with the cap, and nothing is kept`() = stack.app {
        val big = Part("file", FakeMediaProcessor.photo(800, 600) + ByteArray(16 * 1024 * 1024))

        val response = upload(stack.callerWith(Role.ADMIN), purpose = "CATEGORY", files = listOf(big))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(15L * 1024 * 1024, response.json(response.bodyAsText()).getValue("meta").jsonObject.getValue("maxBytes").jsonPrimitive.content.toLong())
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `16 MB photo for a product, its purpose sent after the file, is 413 once it is known to be a photo`() = stack.app {
        val big = Part("file", FakeMediaProcessor.photo(800, 600) + ByteArray(16 * 1024 * 1024))

        val response = upload(stack.callerWith(Role.ADMIN), purposeFirst = false, files = listOf(big))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(emptyList(), storedFiles())
    }

    @Test
    fun `a customer's purpose from the panel is 400 MEDIA_PURPOSE_INVALID - no purpose, no file or two files are 400 too`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)

        val customerPurpose = upload(admin, purpose = "RETURN_EVIDENCE")
        val noPurpose = upload(admin, purpose = null)
        val noFile = upload(admin, files = emptyList())
        val two = upload(admin, files = listOf(Part("file", FakeMediaProcessor.photo(800, 600)), Part("file", FakeMediaProcessor.photo(800, 600))))

        listOf(customerPurpose to "MEDIA_PURPOSE_INVALID", noPurpose to "FIELD_REQUIRED", noFile to "FIELD_REQUIRED", two to "FILE_COUNT_INVALID")
            .forEach { (response, code) ->
                assertEquals(HttpStatusCode.BadRequest, response.status, code)
                assertTrue(code in response.bodyAsText(), response.bodyAsText())
            }
    }

    @Test
    fun `a photo 200 by 200 is 400 IMAGE_TOO_SMALL - and a photo 400 by 320 keeps only 160 and 400`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)

        val small = upload(admin, files = listOf(Part("file", FakeMediaProcessor.photo(200, 200))))
        val medium = upload(admin, files = listOf(Part("file", FakeMediaProcessor.photo(400, 320))))

        assertEquals(HttpStatusCode.BadRequest, small.status)
        assertTrue("IMAGE_TOO_SMALL" in small.bodyAsText())
        assertEquals(listOf(160, 400), medium.json(medium.bodyAsText()).getValue("srcSet").jsonArray.map { it.jsonObject.getValue("width").jsonPrimitive.int })
    }

    @Test
    fun `the client's file name is nowhere - not on the disk, not in the row`() = stack.app {
        val response = upload(stack.callerWith(Role.ADMIN), files = listOf(Part("file", FakeMediaProcessor.photo(800, 600), "../../etc/passwd.jpg")))

        assertEquals(HttpStatusCode.Created, response.status)
        assertTrue(storedFiles().none { "passwd" in it || ".." in it }, storedFiles().toString())
        assertFalse(Files.exists(stack.mediaRoot.parent.resolve("etc")))
        val row = transaction(stack.db) { MediaTbl.selectAll().toList().last() }
        assertFalse(MediaTbl.columns.any { "passwd" in row[it].toString() })
        assertTrue(Files.list(stack.mediaRoot.resolve(".tmp")).use { files -> files.noneMatch { it.name.startsWith("upload-") } }, "the upload's temporary file stays")
    }

    @Test
    fun `an upload writes nothing to the change log`() = stack.app {
        val admin = stack.callerWith(Role.ADMIN)

        assertEquals(HttpStatusCode.Created, upload(admin).status)

        assertEquals(0, transaction(stack.db) { AuditLogTbl.selectAll().where { AuditLogTbl.actorId eq admin.userId }.count() })
    }

    @Test
    fun `an operator and a content editor may upload - support may not, nor anyone without a token`() = stack.app {
        assertEquals(HttpStatusCode.Created, upload(stack.callerWith(Role.OPERATOR), purpose = "PRE_SHIPMENT").status)
        assertEquals(HttpStatusCode.Created, upload(stack.callerWith(Role.CONTENT_EDITOR), purpose = "BLOG").status)
        assertEquals(HttpStatusCode.Forbidden, upload(stack.callerWith(Role.SUPPORT)).status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("$API_V1/admin/media").status)
    }
}
