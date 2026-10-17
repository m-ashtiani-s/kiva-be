package com.kiva.infrastructure.job

import com.kiva.application.usecase.audit.usecase.command.DeleteOldAuditEntriesUc
import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import java.time.LocalTime

/**
 * The jobs of the panel (roadmap section 25), each run by the system and never written to the change log.
 *
 * | job | when |
 * |---|---|
 * | removing the change log's rows past 90 days | daily, 03:30 Tehran |
 */
class PanelJobs(
    private val deleteOldAuditEntries: DeleteOldAuditEntriesUc,
) {
    fun all(): List<ScheduledJob> = listOf(
        ScheduledJob("delete-old-audit-entries", Schedule.DailyAt(LocalTime.of(3, 30), ShopCalendar.ZONE)) {
            deleteOldAuditEntries.execute(DeleteOldAuditEntriesUc.Cmd)
        },
    )
}
