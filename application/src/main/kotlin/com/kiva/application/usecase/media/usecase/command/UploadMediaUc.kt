package com.kiva.application.usecase.media.usecase.command

import com.kiva.application.dto.UploadedMediaDto
import com.kiva.application.port.MediaProcessor
import com.kiva.application.port.MediaStorage
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
import com.kiva.common.valueobject.UserId
import com.kiva.domain.aggregate.media.entity.MediaAsset
import com.kiva.domain.aggregate.media.model.policy.MediaLimitsPolicy
import com.kiva.domain.aggregate.media.model.valueobject.MediaAlt
import com.kiva.domain.aggregate.media.model.valueobject.MediaPurpose
import com.kiva.domain.aggregate.media.model.valueobject.MediaVariant
import com.kiva.domain.aggregate.media.repository.MediaIdGenerator
import com.kiva.domain.aggregate.media.repository.MediaRepository
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A member of staff uploads a photo or a video (`POST /admin/media`; ADMIN, CATALOG_MANAGER, CONTENT_EDITOR, OPERATOR),
 * and gets it back processed (roadmap section 5.2):
 *
 * 1. the form: one file, a staff purpose, an alt of at most 200 (400, all at once);
 * 2. what the file is, from its content, and whether its purpose takes it: the type (415), the size (413), the
 *    dimensions or length (400) (MediaLimitsPolicy);
 * 3. processing, outside any transaction: the tools run for seconds and must not hold a connection;
 * 4. the files moved to their folder in one atomic step;
 * 5. a transaction that inserts the row; if it fails, the folder is removed, so no file is left without its row.
 *
 * The client's file name is never used anywhere: the route doesn't pass it in. The route owns the uploaded temporary
 * file and removes it whatever happens. An upload is not written to the change log (contract).
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
     * @property by the member of staff, from the access token.
     */
    data class Cmd(val files: List<UploadedFile>, val purpose: String?, val alt: String?, val by: UserId) {
        override fun toString() = "Cmd(files=${files.size}, purpose=$purpose, by=${by.value})"
    }

    /** A file part the route saved to a temporary file: where, and how many bytes. Its name is the server's. */
    data class UploadedFile(val path: Path, val bytes: Long)

    override suspend fun execute(command: Cmd): Result<UploadedMediaDto, Failure> {
        logger.info("UploadMediaUc.execute files=${command.files.size} purpose=${command.purpose} bytes=${command.files.sumOf { it.bytes }} by=${command.by.value}")

        val (file, purpose, alt) = Result.zip(
            fileOf(command.files),
            purposeOf(command.purpose),
            MediaAlt.makeNew(command.alt),
            { problems -> Failure.InvalidInput(problems) },
        ) { file, purpose, alt -> Triple(file, purpose, alt) }
            .unwrapOr { return Result.failure(it) }

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
            uploadedBy = command.by,
            now = now,
        )
        mediaStorage.commit(workDir, storageDir)

        var stored = false
        try {
            transactionManager.inTransaction {
                mediaRepository.insert(asset)
                Result.success(Unit)
            }
            stored = true
        } finally {
            // No row, no files: a folder nothing points at would never be cleaned up.
            if (!stored) mediaStorage.delete(storageDir)
        }
        logger.info("UploadMediaUc: ${asset.id.value} stored ($type, ${asset.variants.size} sizes) for $purpose")
        return Result.success(UploadedMediaDto.from(asset))
    }

    private fun fileOf(files: List<UploadedFile>): Result<UploadedFile, ResultFailure> = when (files.size) {
        0 -> Result.failure(FileRequired)
        1 -> Result.success(files.single())
        else -> Result.failure(FileCountInvalid)
    }

    private fun purposeOf(raw: String?): Result<MediaPurpose, ResultFailure> =
        if (raw.isNullOrBlank()) Result.failure(PurposeRequired) else MediaPurpose.makeNewForStaff(raw).forField("purpose")

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
    }

    private companion object {
        val FOLDER_OF_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM")
    }
}
