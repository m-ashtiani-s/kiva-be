package com.kiva.infrastructure.job

import com.kiva.domain.aggregate.order.model.policy.ShopCalendar
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JobSchedulerTest {

    @Test
    fun `a job that throws stops neither itself nor the others`() {
        val failing = AtomicInteger()
        val fine = CountDownLatch(3)
        val failedTwice = CountDownLatch(2)
        val scheduler = JobScheduler(
            listOf(
                ScheduledJob("failing", Schedule.Every(Duration.ofMillis(20))) {
                    failing.incrementAndGet()
                    failedTwice.countDown()
                    error("broken on purpose")
                },
                ScheduledJob("fine", Schedule.Every(Duration.ofMillis(20))) { fine.countDown() },
            ),
            Clock.systemUTC(),
        )

        scheduler.start()
        try {
            assertTrue(failedTwice.await(5, TimeUnit.SECONDS), "the failing job ran again after it threw")
            assertTrue(fine.await(5, TimeUnit.SECONDS), "the other job kept running")
        } finally {
            scheduler.stop()
        }
        val afterStop = failing.get()
        Thread.sleep(100)
        assertTrue(failing.get() <= afterStop + 1, "stopped means stopped")
    }

    @Test
    fun `the daily clean-up runs at 4 in the morning in Tehran, today if it is still ahead, else tomorrow`() {
        val fourInTehran = Schedule.DailyAt(LocalTime.of(4, 0), ShopCalendar.ZONE)

        // 03:00 Tehran (Monday 2026-10-12, UTC+3:30): an hour ahead.
        assertEquals(Duration.ofHours(1), fourInTehran.delayUntilNext(Instant.parse("2026-10-11T23:30:00Z")))
        // 04:00 exactly has just run: the next is tomorrow's.
        assertEquals(Duration.ofHours(24), fourInTehran.delayUntilNext(Instant.parse("2026-10-12T00:30:00Z")))
        // 11:30 Tehran: 16.5 hours to go.
        assertEquals(Duration.ofMinutes(16 * 60 + 30), fourInTehran.delayUntilNext(Instant.parse("2026-10-12T08:00:00Z")))
    }

    @Test
    fun `two jobs can't share a name, and a period must be positive`() {
        val job = ScheduledJob("same", Schedule.Every(Duration.ofMinutes(1))) {}
        assertFailsWith<IllegalArgumentException> { JobScheduler(listOf(job, job), Clock.systemUTC()) }
        assertFailsWith<IllegalArgumentException> { Schedule.Every(Duration.ZERO) }
    }
}
