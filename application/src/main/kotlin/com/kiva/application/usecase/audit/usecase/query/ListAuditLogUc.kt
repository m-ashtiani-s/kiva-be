package com.kiva.application.usecase.audit.usecase.query

import com.kiva.application.dto.AuditLogEntryDto
import com.kiva.application.repository.AuditLogFilter
import com.kiva.application.repository.AuditLogQmRepository
import com.kiva.application.usecase.audit.AuditEntityType
import com.kiva.common.logging.getLogger
import com.kiva.common.pagination.Page
import com.kiva.common.pagination.Pageable
import com.kiva.common.railway.FailureKind
import com.kiva.common.railway.InputProblems
import com.kiva.common.railway.Result
import com.kiva.common.railway.ResultFailure
import com.kiva.common.railway.flattenGroups
import com.kiva.common.railway.unwrapOr
import com.kiva.common.railway.zip
import com.kiva.common.text.normalizeDigits
import com.kiva.common.transaction.TransactionManager
import com.kiva.common.usecase.QueryUseCase
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The panel's change log (`GET /admin/audit-log`, ADMIN only): newest first, all of it or one thing's history
 * (`entityType` + `entityId`), one actor's, or a range of days.
 *
 * `from` and `to` are Tehran days, both kept whole: `from` starts at its 00:00 and `to` ends at the next day's 00:00
 * (exclusive). `from` after `to` is 400 on `to`. Rows older than 90 days are gone, so an older range is just empty.
 */
class ListAuditLogUc(
    private val transactionManager: TransactionManager,
    private val auditLogQmRepository: AuditLogQmRepository,
) : QueryUseCase<ListAuditLogUc.Qry, Page<AuditLogEntryDto>, ListAuditLogUc.Failure> {

    private val logger = getLogger()

    /** Each filter as the query string had it; null when not given. */
    data class Qry(
        val pageable: Pageable,
        val entityType: String? = null,
        val entityId: String? = null,
        val actorId: String? = null,
        val from: String? = null,
        val to: String? = null,
    )

    override suspend fun execute(query: Qry): Result<Page<AuditLogEntryDto>, Failure> {
        logger.info(
            "ListAuditLogUc.execute entityType=${query.entityType} entityId=${query.entityId} actorId=${query.actorId} " +
                "from=${query.from} to=${query.to} page=${query.pageable.page}",
        )
        val filter = Result.zip(
            Result.zip(entityTypeOf(query.entityType), entityIdOf(query.entityId), actorIdOf(query.actorId), ::InputProblems) { type, id, actor ->
                Triple(type, id, actor)
            },
            Result.zip(dayOf(query.from, "from"), dayOf(query.to, "to"), ::InputProblems) { from, to -> from to to },
            { problems -> Failure.InvalidInput(problems.flattenGroups()) },
        ) { (type, id, actor), (from, to) ->
            if (from != null && to != null && from.isAfter(to)) return Result.failure(Failure.InvalidInput(listOf(DateRangeInvalid)))
            AuditLogFilter(
                entityType = type,
                entityId = id,
                actorId = actor,
                from = from?.atStartOfDay(ShopCalendar.ZONE)?.toInstant(),
                until = to?.plusDays(1)?.atStartOfDay(ShopCalendar.ZONE)?.toInstant(),
            )
        }.unwrapOr { return Result.failure(it) }

        return transactionManager.inTransaction {
            Result.success(auditLogQmRepository.find(filter, query.pageable).map(AuditLogEntryDto::from))
        }
    }

    private fun entityTypeOf(raw: String?): Result<AuditEntityType?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return AuditEntityType.entries.firstOrNull { it.name == text }?.let { Result.success(it) } ?: Result.failure(EntityTypeInvalid)
    }

    private fun entityIdOf(raw: String?): Result<String?, ResultFailure> {
        val text = raw?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return if (text.length > MAX_ENTITY_ID) Result.failure(EntityIdTooLong) else Result.success(text)
    }

    private fun actorIdOf(raw: String?): Result<Long?, ResultFailure> {
        val text = raw?.normalizeDigits()?.trim()?.ifEmpty { null } ?: return Result.success(null)
        val id = text.toLongOrNull()?.takeIf { it > 0 } ?: return Result.failure(ActorIdInvalid)
        return Result.success(id)
    }

    private fun dayOf(raw: String?, field: String): Result<LocalDate?, ResultFailure> {
        val text = raw?.normalizeDigits()?.trim()?.ifEmpty { null } ?: return Result.success(null)
        return try {
            Result.success(LocalDate.parse(text))
        } catch (_: DateTimeParseException) {
            Result.failure(DateInvalid(field))
        }
    }

    data object EntityTypeInvalid : ResultFailure("AUDIT_ENTITY_TYPE_INVALID", FailureKind.INVALID, field = "entityType")

    data object EntityIdTooLong :
        ResultFailure("ENTITY_ID_TOO_LONG", FailureKind.INVALID, field = "entityId", meta = mapOf("maxLength" to MAX_ENTITY_ID))

    data object ActorIdInvalid : ResultFailure("NUMBER_INVALID", FailureKind.INVALID, field = "actorId")

    /** `from` or `to` isn't a `YYYY-MM-DD` day. */
    data class DateInvalid(val name: String) : ResultFailure("DATE_INVALID", FailureKind.INVALID, field = name)

    /** `from` is after `to`. */
    data object DateRangeInvalid : ResultFailure("DATE_RANGE_INVALID", FailureKind.INVALID, field = "to")

    sealed class Failure(code: String, kind: FailureKind, causes: List<ResultFailure>) : ResultFailure(code, kind, causes = causes) {
        data class InvalidInput(val problems: List<ResultFailure>) : Failure("VALIDATION_ERROR", FailureKind.INVALID, problems)
    }

    private companion object {
        const val MAX_ENTITY_ID = 64
    }
}
