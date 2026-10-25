package com.kiva.application.usecase.content.usecase.command

import com.kiva.application.port.AuditEntry
import com.kiva.application.port.AuditTrail
import com.kiva.application.port.PanelActor
import com.kiva.application.usecase.audit.AuditAction
import com.kiva.application.usecase.audit.AuditDiff
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.domain.aggregate.faq.entity.FaqBook
import com.kiva.domain.aggregate.faq.model.FaqDraft
import com.kiva.domain.aggregate.faq.repository.FaqRepository
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Clock

/**
 * The panel saves the whole FAQ (`PUT /admin/faq/groups`; ADMIN, CONTENT_EDITOR; roadmap phase 5, decisions 9 and 18):
 * the form's shape first, every problem at once (400); then, under the FAQ's lock, each question's id must be one the
 * shop has (400 FAQ_ITEM_NOT_FOUND on its path). New questions get their ids; groups and questions left out are gone.
 * One FAQ_SAVED row when something changed, with the counts before and after (decision 20). The route answers with
 * ListFaqForEditUc.
 */
class SaveFaqUc(
    private val transactionManager: TransactionManager,
    private val faqRepository: FaqRepository,
    private val sanitizer: HtmlSanitizer,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<SaveFaqUc.Cmd, Unit, SaveFaqUc.Failure> {

    private val logger = getLogger()

    data class Cmd(val groups: List<FaqDraft.GroupInput>, val actor: PanelActor) {
        override fun toString(): String = "Cmd(${groups.size} groups, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<Unit, Failure> {
        logger.info("SaveFaqUc.execute groups=${command.groups.size} by=${command.actor.userId.value}")
        val draft = FaqDraft.makeNew(command.groups, sanitizer).unwrapOr { return Result.failure(Failure.InvalidInput(it.causes)) }

        return transactionManager.inTransaction {
            faqRepository.lock()
            val before = faqRepository.load()
            val unknown = draft.unknownIds(before.itemIds)
            if (unknown.isNotEmpty()) return@inTransaction Result.failure(Failure.InvalidInput(unknown))
            if (draft.isSameAs(before)) {
                logger.info("SaveFaqUc: the FAQ is unchanged")
                return@inTransaction Result.success(Unit)
            }
            val after = draft.toBook(faqRepository::nextItemId)
            faqRepository.save(after)
            auditTrail.record(AuditEntry(clock.instant(), command.actor, AuditAction.FAQ_SAVED, AuditEntityType.FAQ, ENTITY_ID, null, changesOf(before, after)))
            logger.info("SaveFaqUc: ${after.groups.size} groups and ${after.questionsCount} questions saved")
            Result.success(Unit)
        }
    }

    private fun changesOf(before: FaqBook, after: FaqBook) = AuditDiff.build {
        field("groups", before.groups.size, after.groups.size)
        field("questions", before.questionsCount, after.questionsCount)
        // The texts are long and the editor's: only that something in them changed.
        longText("content", before.hashCode().toString(), after.hashCode().toString())
    }

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        /** The change log's id for the FAQ, which is one thing. */
        const val ENTITY_ID = "faq"
    }
}
