package com.kiva.application.util

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A clock that stands still until a test moves it, so limits counted over minutes and hours can be tested in a moment. */
class MutableClock(start: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {

    @Volatile
    var now: Instant = start
        private set

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = if (zone == this.zone) this else error("MutableClock is only used in UTC")
}
