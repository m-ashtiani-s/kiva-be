package com.kiva.application.usecase.media.usecase.command

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.MediaStorage
import com.kiva.application.usecase.media.MediaUploader
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.forField
import com.kiva.common.railway.mapOnFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.media.repository.MediaIdGenerator
import com.kiva.domain.aggregate.media.repository.MediaRepository
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A member of staff (`POST /admin/media`; ADMIN, CATALOG_MANAGER, CONTENT_EDITOR, OPERATOR) or a customer
 * (`POST /me/uploads`, phase 7) uploads a photo or a video, and gets it back processed (roadmap section 5.2):
 *
 * 1. the form: one file, a purpose of the [MediaUploader]'s own (staff's or the customer's), an alt of at most 200
 *    (400, all at once);
 * 2. a customer only: at most 20 uploads in the last hour (429 with how long to wait, MediaLimitsPolicy), counted
 *    before anything is processed;
 * 3. what the file is, from its content, and whether its purpose takes it: the type (415), the size (413), the
 *    dimensions or length (400) (MediaLimitsPolicy);
 * 4. processing, outside any transaction: the tools run for seconds and must not hold a connection;
 * 5. the files moved to their folder in one atomic step;
 * 6. a transaction that inserts the row; for a customer, under the lock of their uploads and after counting again, so
 *    uploads sent at once can't pass the cap (roadmap section 12.2, row 29). If it fails or is refused, the folder is
 *    removed, so no file is left without its row.
 *
 * The client's file name is never used anywhere: the route doesn't pass it in. The route owns the uploaded temporary
 * file and removes it whatever happens. An upload is not written to the change log (contract), staff's or a customer's.
 *
 * @return the stored file, as the 201 shows it (a documented exception to "commands return an id").
 */
class UploadMediaUc(
    private val transactionManager: TransactionManager,
    private val mediaProcessor: MediaProcessor,
    private val mediaStorage: MediaStorage,
    private val mediaRepository: MediaRepository,
    private val mediaIdGenerator: MediaIdGenerator,
    private val clock: Clock,
) : CommandUseCase<UploadMediaUc.Cmd, UploadedMediaDto, UploadMediaUc.Failure> {

    private val logger = getLogger()

    /**
     * @property files every file part of the request, as the route stored them: exactly one is right.
     * @property purpose, alt the form's fields as they came; null when not sent.
     * @property by who uploads, from the access token and the route.
     */
    data class Cmd(val files: List<UploadedFile>, val purpose: String?, val alt: String?, val by: MediaUploader) {
        override fun toString() = "Cmd(files=${files.size}, purpose=$purpose, by=${by.userId.value})"
    }

    /** A file part the route saved to a temporary file: where, and how many bytes. Its name is the server's. */
    data class UploadedFile(val path: Path, val bytes: Long)

    override suspend fun execute(command: Cmd): Result<UploadedMediaDto, Failure> {
        val uploader = command.by
        logger.info(
            "UploadMediaUc.execute files=${command.files.size} purpose=${command.purpose} bytes=${command.files.sumOf { it.bytes }} " +
                "by=${uploader.userId.value} customer=${uploader is MediaUploader.Customer}",
        )

        val (file, purpose, alt) = Result.zip(
            fileOf(command.files),
            purposeOf(command.purpose, uploader),
            MediaAlt.makeNew(command.alt),
            { problems -> Failure.InvalidInput(problems) },
        ) { file, purpose, alt -> Triple(file, purpose, alt) }
            .unwrapOr { return Result.failure(it) }

        if (uploader is MediaUploader.Customer) {
            // The cheap count first: a customer over the cap costs no processing.
            val refusal = transactionManager.inTransaction { Result.success(capRefusalOf(uploader, clock.instant())) }.getOrNull()
            if (refusal != null) return Result.failure(Failure.RateLimited(refusal.meta))
        }

        val probe = mediaProcessor.probe(file.path)
        val type = MediaLimitsPolicy.check(purpose, probe, file.bytes)
            .mapOnFailure(::failureOf)
            .unwrapOr { return Result.failure(it) }

        val now = clock.instant()
        val id = mediaIdGenerator.next()
        val storageDir = "${FOLDER_OF_MONTH.format(now.atOffset(ZoneOffset.UTC))}/${id.value}"
        val workDir = mediaStorage.newWorkDir()
        val processed = try {
            mediaProcessor.process(file.path, probe, workDir)
        } catch (e: Exception) {
            mediaStorage.discard(workDir)
            throw e
        }
        val asset = MediaAsset.upload(
            id = id,
            purpose = purpose,
            type = type,
            width = processed.width,
            height = processed.height,
            durationSec = processed.durationSec,
            bytes = processed.bytes,
            blurhash = processed.blurhash,
            variants = processed.files.map { MediaVariant.of(it.width, "$storageDir/${it.name}") },
            posterPath = processed.posterFile?.let { "$storageDir/$it" },
            alt = alt,
            storageDir = storageDir,
            uploadedBy = uploader.userId,
            now = now,
        )
        mediaStorage.commit(workDir, storageDir)

        var stored = false
        try {
            val inserted = transactionManager.inTransaction {
                if (uploader is MediaUploader.Customer) {
                    // Counted again under the lock of their uploads: those sent at once wait for each other here.
                    mediaRepository.lockUploadsOf(uploader.userId)
                    capRefusalOf(uploader, now)?.let { return@inTransaction Result.failure(Failure.RateLimited(it.meta)) }
                }
                mediaRepository.insert(asset)
                Result.success(Unit)
            }
            if (inserted is Result.Failure) {
                logger.info("UploadMediaUc: ${asset.id.value} refused at the insert, the customer's cap was reached meanwhile")
                return inserted
            }
            stored = true
        } finally {
            // No row, no files: a folder nothing points at would never be cleaned up.
            if (!stored) mediaStorage.delete(storageDir)
        }
        logger.info("UploadMediaUc: ${asset.id.value} stored ($type, ${asset.variants.size} sizes) for $purpose")
        return Result.success(UploadedMediaDto.from(asset))
    }

    /** Why [customer] may not upload one more at [now], or null if they may; in the caller's transaction. */
    private fun capRefusalOf(customer: MediaUploader.Customer, now: Instant): MediaLimitsPolicy.UploadRefusal? =
        MediaLimitsPolicy.customerUploadRefusal(mediaRepository.uploadTimesOf(customer.userId, now.minus(MediaLimitsPolicy.CUSTOMER_UPLOAD_WINDOW)), now)

    private fun fileOf(files: List<UploadedFile>): Result<UploadedFile, ResultFailure> = when (files.size) {
        0 -> Result.failure(FileRequired)
        1 -> Result.success(files.single())
        else -> Result.failure(FileCountInvalid)
    }

    private fun purposeOf(raw: String?, uploader: MediaUploader): Result<MediaPurpose, ResultFailure> = when {
        raw.isNullOrBlank() -> Result.failure(PurposeRequired)
        uploader is MediaUploader.Staff -> MediaPurpose.makeNewForStaff(raw).forField("purpose")
        else -> MediaPurpose.makeNewForCustomer(raw).forField("purpose")
    }

    private fun failureOf(failure: MediaLimitsPolicy.Failure): Failure = when (failure) {
        is MediaLimitsPolicy.Failure.TypeNotAllowed -> Failure.TypeNotAllowed(failure.allowed, failure.hint)
        is MediaLimitsPolicy.Failure.TooLarge -> Failure.TooLarge(failure.maxBytes)
        MediaLimitsPolicy.Failure.ImageTooSmall, MediaLimitsPolicy.Failure.ImageTooLarge, is MediaLimitsPolicy.Failure.VideoTooLong ->
            Failure.InvalidInput(listOf(failure))
    }

    data object FileRequired : ResultFailure("FIELD_REQUIRED", FailureKind.INVALID, field = "file")

    data object FileCountInvalid : ResultFailure("FILE_COUNT_INVALID", FailureKind.INVALID, field = "file")

    data object PurposeRequired : ResultFailure("FIELD_REQUIRED", FailureKind.INVALID, field = "purpose")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure> = emptyList(), meta: Map<String, Any> = emptyMap()) :
        ResultFailure(code, kind, causes = causes, meta = meta) {

        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)

        /** The file is more than its type takes for its purpose, or more than any upload while it was being read. */
        data class TooLarge(val maxBytes: Long) : Failure("UPLOAD_TOO_LARGE", FailureKind.PAYLOAD_TOO_LARGE, meta = mapOf("maxBytes" to maxBytes))

        data class TypeNotAllowed(val allowed: List<String>, val hint: String?) : Failure(
            "UPLOAD_TYPE_NOT_ALLOWED", FailureKind.UNSUPPORTED_MEDIA_TYPE,
            meta = buildMap {
                put("allowed", allowed)
                hint?.let { put("hint", it) }
            },
        )

        /** A customer reached 20 uploads in the last hour (429, with `retryAfterSeconds`). */
        data class RateLimited(val details: Map<String, Any>) : Failure("RATE_LIMITED", FailureKind.RATE_LIMITED, meta = details)
    }

    private companion object {
        val FOLDER_OF_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM")
    }
}
