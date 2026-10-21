package com.kiva.infrastructure.httpserver.media

import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import java.nio.file.Files
import java.nio.file.Path

/** What an upload's form turned out to be, as [withUpload] read it. */
sealed interface UploadForm {
    /** Its file parts, stored to temporary files, and its text fields by name. */
    class Received(val files: List<UploadMediaUc.UploadedFile>, val fields: Map<String, String>) : UploadForm

    /** A file passed [maxBytes]: reading stopped there (413). */
    class TooLarge(val maxBytes: Long) : UploadForm
}

/**
 * Reads an upload's multipart form as it arrives, for `POST /admin/media` and `POST /me/uploads` alike, and gives it
 * to [block]: each file part goes to a temporary file from [newUploadFile] (whatever its part's name: exactly one is
 * right, FILE_COUNT_INVALID), and reading stops the moment one passes the cap [capOf] gives for the text fields read so
 * far (the purpose's own cap when `purpose` came before the file, the largest one otherwise). Only the text fields of
 * [fieldNames] are kept, and the client's file name is never read.
 *
 * Every temporary file is removed when [block] is done, whatever happened.
 */
suspend fun ApplicationCall.withUpload(
    fieldNames: Set<String>,
    newUploadFile: () -> Path,
    capOf: (Map<String, String>) -> Long,
    block: suspend (UploadForm) -> Unit,
) {
    val files = mutableListOf<UploadMediaUc.UploadedFile>()
    val spooled = mutableListOf<Path>()
    val fields = mutableMapOf<String, String>()
    try {
        val form = try {
            var overCap: Long? = null
            receiveMultipart(formFieldLimit = MediaLimitsPolicy.MAX_UPLOAD_BYTES + 1).forEachPart { part ->
                try {
                    val name = part.name
                    when {
                        overCap != null -> Unit
                        part is PartData.FormItem && name != null && name in fieldNames -> fields[name] = part.value
                        part is PartData.FileItem -> {
                            val cap = capOf(fields)
                            val file = newUploadFile().also { spooled.add(it) }
                            val bytes = part.provider().copyToFileWithin(file, cap)
                            if (bytes == null) overCap = cap else files.add(UploadMediaUc.UploadedFile(file, bytes))
                        }
                    }
                } finally {
                    part.release()
                }
                overCap?.let { throw UploadTooLarge(it) }
            }
            UploadForm.Received(files.toList(), fields.toMap())
        } catch (e: UploadTooLarge) {
            UploadForm.TooLarge(e.maxBytes)
        }
        block(form)
    } finally {
        spooled.forEach { Files.deleteIfExists(it) }
    }
}

/** Stops reading the request: a file passed its cap. */
private class UploadTooLarge(val maxBytes: Long) : RuntimeException(null, null, false, false)
