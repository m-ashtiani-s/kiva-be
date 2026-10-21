package com.kiva.application.usecase.content.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.application.usecase.product.MomentField
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.announcement.entity.AnnouncementBar
import com.kiva.domain.aggregate.announcement.model.AnnouncementDraft
import com.kiva.domain.aggregate.announcement.repository.AnnouncementBarRepository
import java.time.Clock

/**
 * The panel saves the whole announcement bar (`PUT /admin/announcements`; ADMIN, CONTENT_EDITOR; roadmap phase 5,
 * decision 11): the form's shape first, every problem at once (400); then, under the bar's lock, each id must be one the
 * shop has (400 ANNOUNCEMENT_NOT_FOUND on its path). New announcements get their ids; those left out are gone. One
 * ANNOUNCEMENTS_SAVED row when something changed, with the counts (decision 20). The route answers with ListAnnouncementsUc.
 */
class SaveAnnouncementsUc(
    private val transactionManager: TransactionManager,
    private val announcementBarRepository: AnnouncementBarRepository,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<SaveAnnouncementsUc.Cmd, Unit, SaveAnnouncementsUc.Failure> {

    private val logger = getLogger()

    /** An announcement as the form sent it; [startsAt] and [endsAt] as text. */
    data class Fields(val id: Long?, val icon: String?, val text: String?, val url: String?, val startsAt: String?, val endsAt: String?)

    data class Cmd(val items: List<Fields>, val actor: PanelActor) {
        override fun toString(): String = "Cmd(${items.size} announcements, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("SaveAnnouncementsUc.execute items=${command.items.size} by=${command.actor.userId.value}")
        val inputs = command.items.map {
            AnnouncementDraft.Input(it.id, it.icon, it.text, it.url, MomentField.makeNew(it.startsAt), MomentField.makeNew(it.endsAt))
        }
        val draft = AnnouncementDraft.makeNew(inputs).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            announcementBarRepository.lock()
            val before = announcementBarRepository.load()
            val unknown = draft.unknownIds(before.ids)
            if (unknown.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(unknown))
            if (draft.isSameAs(before)) {
                logger.info("SaveAnnouncementsUc: the bar is unchanged")
                return@inTransaction Result.success(Unit)
            }
            val after = draft.toBar(announcementBarRepository::nextId)
            announcementBarRepository.save(after)
            auditTrail.record(
                AuditEntry(clock.instant(), command.actor, AuditAction.ANNOUNCEMENTS_SAVED, AuditEntityType.ANNOUNCEMENT, ENTITY_ID, null, changesOf(before, after)),
            )
            logger.info("SaveAnnouncementsUc: ${after.items.size} announcements saved")
            Result.success(Unit)
        }
    }

    private fun changesOf(before: AnnouncementBar, after: AnnouncementBar) = AuditDiff.build {
        field("count", before.items.size, after.items.size)
        // Only that the texts, links or times changed: the list is the form's, not the log's.
        longText("items", before.hashCode().toString(), after.hashCode().toString())
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        /** The change log's id for the bar, which is one thing. */
        const val ENTITY_ID = "announcements"
    }
}
