package com.kiva.infrastructure.httpserver.controller.httprest.web

import com.kiva.application.usecase.admin.usecase.command.CheckPanelAccessUc
import com.kiva.application.usecase.media.MediaUploader
import com.kiva.application.usecase.media.usecase.command.UploadMediaUc
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.user.model.valueobject.Role
import com.kiva.infrastructure.common.http.respondFailure
import com.kiva.infrastructure.common.http.respondResult
import com.kiva.infrastructure.httpserver.auth.accessPrincipal
import com.kiva.infrastructure.httpserver.auth.requireRoles
import com.kiva.infrastructure.httpserver.controller.httprest.model.response.MediaAssetHttpResponse
import com.kiva.infrastructure.httpserver.media.UploadForm
import com.kiva.infrastructure.httpserver.media.withUpload
import com.kiva.infrastructure.media.MediaUrls
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.nio.file.Path

/** The use cases of the panel's uploads, as the route needs them. */
class AdminMediaUseCases(
    val checkPanelAccess: CheckPanelAccessUc,
    val uploadMedia: UploadMediaUc,
)

/**
 * The panel's uploads (contract tag `Admin · Catalog`, `POST /admin/media`; ADMIN, CATALOG_MANAGER, CONTENT_EDITOR,
 * OPERATOR). Mounted under `/api/v1`.
 *
 * The request is read as it arrives (withUpload): each file part goes to a temporary file from [newUploadFile] and
 * reading stops the moment it passes the cap (413 at once): the purpose's own cap when `purpose` came before the
 * file, the largest of any purpose otherwise. The client's file name is never read. Every temporary file is removed
 * when the request is done, whatever happened.
 */
fun Route.webV1AdminMedia(useCases: AdminMediaUseCases, newUploadFile: () -> Path, mediaUrls: MediaUrls) {
    requireRoles(useCases.checkPanelAccess, Role.ADMIN, Role.CATALOG_MANAGER, Role.CONTENT_EDITOR, Role.OPERATOR) {
        post("/admin/media") {
            call.withUpload(setOf("purpose", "alt"), newUploadFile, { fields -> capOf(fields["purpose"]) }) { form ->
                when (form) {
                    is UploadForm.TooLarge -> call.respondFailure(UploadMediaUc.Failure.TooLarge(form.maxBytes))
                    is UploadForm.Received -> {
                        val by = MediaUploader.Staff(call.accessPrincipal().userId)
                        val command = UploadMediaUc.Cmd(files = form.files, purpose = form.fields["purpose"], alt = form.fields["alt"], by = by)
                        call.respondResult(useCases.uploadMedia.execute(command), HttpStatusCode.Created) { MediaAssetHttpResponse.from(it, mediaUrls) }
                    }
                }
            }
        }
    }
}

/** The cap while a file is read: its purpose's, when it is already known and valid; the largest of all otherwise. */
private fun capOf(purpose: String?): Long =
    MediaPurpose.makeNewForStaff(purpose).getOrNull()?.let { MediaLimitsPolicy.limitsOf(it).maxUploadBytes } ?: MediaLimitsPolicy.MAX_UPLOAD_BYTES
