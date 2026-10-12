package com.kiva.infrastructure.job

import com.kiva.common.logging.getLogger
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the shop's scheduled jobs inside the app itself (roadmap section 15): one coroutine per job, all under one
 * SupervisorJob, so a job that throws stops neither itself (it runs again at its next time) nor the others. Started when
 * the app has started, stopped when it stops; off with `kiva.jobs.enabled = false`.
 *
 * There is no lock between two instances of the app running the same job: each job is right however often and however
 * concurrently it runs, because its query only finds candidates and each candidate is decided by its aggregate, in a
 * transaction of its own, with its own locks.
 */
class JobScheduler(private val jobs: List<ScheduledJob>, private val clock: Clock) {

    private val logger = getLogger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("jobs"))

    init {
        require(jobs.map { it.name }.distinct().size == jobs.size) { "Two jobs with one name: ${jobs.map { it.name }}" }
    }

    fun start() {
        logger.info("JobScheduler: starting ${jobs.joinToString { "${it.name} (${it.schedule})" }}")
        jobs.forEach { job -> scope.launch(CoroutineName("job-${job.name}")) { loop(job) } }
    }

    fun stop() {
        logger.info("JobScheduler: stopping")
        scope.cancel()
    }

    private suspend fun loop(job: ScheduledJob) {
        while (true) {
            delay(job.schedule.delayUntilNext(clock.instant()).toMillis())
            runOnce(job)
        }
    }

    /** One run of [job]. Whatever it throws is logged, and it runs again at its next time. */
    suspend fun runOnce(job: ScheduledJob) {
        try {
            job.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Job ${job.name} failed; it runs again at its next time", e)
        }
    }
}

/** One scheduled job: its [name] for the log, when it runs, and what it does. */
class ScheduledJob(val name: String, val schedule: Schedule, val run: suspend () -> Unit)

/** When a job runs. */
sealed interface Schedule {

    /** How long from [now] until the next run. */
    fun delayUntilNext(now: Instant): Duration

    /** Every [period], the first one [period] after the app started. */
    data class Every(val period: Duration) : Schedule {
        init {
            require(period > Duration.ZERO) { "A job's period must be positive: $period" }
        }

        override fun delayUntilNext(now: Instant): Duration = period
    }

    /** Once a day, at [time] in [zone]. */
    data class DailyAt(val time: LocalTime, val zone: ZoneId) : Schedule {
        override fun delayUntilNext(now: Instant): Duration {
            val here = now.atZone(zone)
            val today = here.with(time)
            val next = if (today.isAfter(here)) today else here.plusDays(1).with(time)
            return Duration.between(now, next.toInstant())
        }
    }
}
