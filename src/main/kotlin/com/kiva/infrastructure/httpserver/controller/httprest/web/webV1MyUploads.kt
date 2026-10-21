package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.media.MediaUploader
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.ACCESS
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MediaAssetHttpResponse
import com.kiva.infrastructure.httpserver.media.UploadForm
import com.kiva.infrastructure.httpserver.media.withUpload
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.nio.file.Path

/**
 * A customer's uploads (contract tag `Uploads`, `POST /me/uploads`): a photo or video for a return (and, from phase 8,
 * a review), processed exactly as the panel's (UploadMediaUc with a MediaUploader.Customer). Mounted under `/api/v1`;
 * a login is needed.
 *
 * Read as it arrives, like the panel's (withUpload): the cap of the customer's purpose when `purpose` came before the
 * file, the largest customer cap (50 MB) otherwise, and the use case checks the size again once the file is known to
 * be a photo (8 MB). `alt` isn't read: a customer's upload has none. Every temporary file is removed when the request
 * is done.
 */
fun Route.webV1MyUploads(uploadMedia: UploadMediaUc, newUploadFile: () -> Path, mediaUrls: MediaUrls) {
    authenticate(ACCESS) {
        post("/me/uploads") {
            call.withUpload(setOf("purpose"), newUploadFile, { fields -> capOf(fields["purpose"]) }) { form ->
                when (form) {
                    is UploadForm.TooLarge -> call.respondFailure(UploadMediaUc.Failure.TooLarge(form.maxBytes))
                    is UploadForm.Received -> {
                        val by = MediaUploader.Customer(call.accessPrincipal().userId)
                        val command = UploadMediaUc.Cmd(files = form.files, purpose = form.fields["purpose"], alt = null, by = by)
                        call.respondResult(uploadMedia.execute(command), HttpStatusCode.Created) { MediaAssetHttpResponse.from(it, mediaUrls) }
                    }
                }
            }
        }
    }
}

/** The cap while a file is read: its customer purpose's, when it is already known; the largest of a customer's otherwise. */
private fun capOf(purpose: String?): Long =
    MediaPurpose.makeNewForCustomer(purpose).getOrNull()?.let { MediaLimitsPolicy.limitsOf(it).maxUploadBytes } ?: MediaLimitsPolicy.MAX_CUSTOMER_UPLOAD_BYTES
