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
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.forField
import com.kiva.common.railway.unwrapOr
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.CommandUseCase
import com.kiva.common.valueobject.SeoMeta
import com.kiva.common.valueobject.Slug
import com.kiva.domain.aggregate.page.entity.StaticPage
import com.kiva.domain.aggregate.page.model.PageDraft
import com.kiva.domain.aggregate.page.repository.StaticPageRepository
import com.kiva.domain.aggregate.product.model.valueobject.HtmlSanitizer
import java.time.Clock

/**
 * The panel writes a static page (`PUT /admin/pages/{slug}`; ADMIN, CONTENT_EDITOR; roadmap phase 5, decision 7): a page
 * nobody wrote yet is made, one that is there is rewritten, both the same way. The path's slug must be one (400
 * SLUG_INVALID); the body's, if sent, must be the path's (400 SLUG_IMMUTABLE); then the form, every problem at once. The
 * content is sanitized before it is measured. Under the page's lock, so two saves of one page are one after the other.
 * One PAGE_SAVED row when something changed: the title and search engine fields as they were and are, the content only
 * as "changed" (decision 20). Returns the slug; the route answers with GetPageUc.
 */
class SavePageUc(
    private val transactionManager: TransactionManager,
    private val staticPageRepository: StaticPageRepository,
    private val sanitizer: HtmlSanitizer,
    private val auditTrail: AuditTrail,
    private val clock: Clock,
) : CommandUseCase<SavePageUc.Cmd, Slug, SavePageUc.Failure> {

    private val logger = getLogger()

    /** The form as it came; [bodySlug] is the body's `slug`, null when it was left out. */
    data class Fields(
        val bodySlug: String?,
        val title: String?,
        val content: String?,
        val seoTitle: String?,
        val seoDescription: String?,
        val seoNoIndex: Boolean?,
    ) {
        override fun toString(): String = "Fields(title=$title, content=${content?.length} chars)"
    }

    data class Cmd(val pathSlug: String, val fields: Fields, val actor: PanelActor) {
        override fun toString(): String = "Cmd(slug=$pathSlug, actor=${actor.userId.value})"
    }

    override suspend fun execute(command: Cmd): Result<Slug, Failure> {
        logger.info("SavePageUc.execute slug=${command.pathSlug.take(MAX_LOGGED)} by=${command.actor.userId.value}")
        val slug = Slug.makeNew(command.pathSlug).forField("slug").unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it))) }
        val bodySlug = command.fields.bodySlug
        if (bodySlug != null && bodySlug != slug.value) return Result.failure(Failure.InvalidInput(listOf(SlugImmutable)))
        val fields = command.fields
        val draft = PageDraft.makeNew(fields.title, fields.content, fields.seoTitle, fields.seoDescription, fields.seoNoIndex, sanitizer)
            .unwrapOr { return Result.failure(Failure.InvalidInput(listOf(it).flattenGroups())) }

        return transactionManager.inTransaction {
            staticPageRepository.lock(slug)
            val now = clock.instant()
            val before = staticPageRepository.find(slug)
            val after = if (before == null) StaticPage.write(slug, draft, command.actor.userId, now) else before.rewrite(draft, command.actor.userId, now)
            if (after == null) {
                logger.info("SavePageUc: page ${slug.value} unchanged")
                return@inTransaction Result.success(slug)
            }
            staticPageRepository.save(after)
            val changes = changesOf(before?.draft, after.draft)
            auditTrail.record(AuditEntry(now, command.actor, AuditAction.PAGE_SAVED, AuditEntityType.PAGE, slug.value, after.draft.title, changes))
            logger.info("SavePageUc: page ${slug.value} ${if (before == null) "written" else "rewritten"}")
            Result.success(slug)
        }
    }

    private fun changesOf(before: PageDraft?, after: PageDraft) = AuditDiff.build {
        field("title", before?.title, after.title)
        longText("content", before?.content, after.content)
        json("seo", before?.seo?.let(::seoOf), seoOf(after.seo))
    }

    private fun seoOf(seo: SeoMeta) = mapOf("title" to seo.title, "description" to seo.description, "noIndex" to seo.noIndex)

    /** The body's slug isn't the path's: a page is never renamed. */
    data object SlugImmutable : ResultFailure("SLUG_IMMUTABLE", FailureKind.INVALID, field = "slug")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        const val MAX_LOGGED = 100
    }
}
