package com.kiva.domain.aggregate.newsletter

import com.kiva.common.railway.Result
import com.kiva.domain.aggregate.newsletter.entity.NewsletterSubscription
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionSource
import com.kiva.domain.aggregate.newsletter.model.valueobject.SubscriptionStatus
import com.kiva.domain.aggregate.newsletter.model.valueobject.UnsubscribeToken
import com.kiva.domain.aggregate.user.model.valueobject.Email
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A newsletter subscriber (roadmap phase 13, decisions 12, 13 and 15): its token, its source, subscribing again and leaving. */
class NewsletterSubscriptionTest {

    private val now = Instant.parse("2026-10-23T08:00:00Z")
    private val sara = (Email.makeNew("Sara@Example.com") as Result.Success).value
    private val token = UnsubscribeToken.of("nl_" + "aB3".repeat(10) + "xy")!!

    private fun stored(status: SubscriptionStatus) = NewsletterSubscription.constructFromPersisted(
        7, sara, status, "footer", token, now.minus(Duration.ofDays(30)), if (status == SubscriptionStatus.UNSUBSCRIBED) now.minus(Duration.ofDays(3)) else null,
    )

    @Test
    fun `a token is nl_ and 32 Latin letters and digits - random ones differ - and never printed`() {
        val one = UnsubscribeToken.random()
        assertTrue(Regex("nl_[0-9A-Za-z]{32}").matches(one.value), one.value)
        assertNotEquals(one, UnsubscribeToken.random())
        assertEquals(token, UnsubscribeToken.of("  ${token.value} "))
        listOf("nl_short", "NL_" + "a".repeat(32), "nl_" + "a".repeat(31) + "-", "nl_" + "a".repeat(33), "").forEach { assertNull(UnsubscribeToken.of(it), it) }
        assertTrue(token.value !in token.toString())
    }

    @Test
    fun `a source of its shape is kept - anything else is dropped, never refused`() {
        assertEquals("footer", SubscriptionSource.of(" footer "))
        assertEquals("home_popup-2", SubscriptionSource.of("home_popup-2"))
        listOf("Footer", "home page", "a".repeat(31), "", null, "فوتر").forEach { assertNull(SubscriptionSource.of(it), "$it") }
    }

    @Test
    fun `subscribing again - an unsubscribed one is ACTIVE again from now with its token, an active one stays`() {
        val (back, outcome) = stored(SubscriptionStatus.UNSUBSCRIBED).subscribedAgain(now)
        assertEquals(NewsletterSubscription.Outcome.REACTIVATED, outcome)
        assertEquals(listOf(SubscriptionStatus.ACTIVE, now, null, token), listOf(back.status, back.subscribedAt, back.unsubscribedAt, back.token))

        val active = stored(SubscriptionStatus.ACTIVE)
        assertEquals(active to NewsletterSubscription.Outcome.ALREADY_ACTIVE, active.subscribedAgain(now))
    }

    @Test
    fun `leaving - UNSUBSCRIBED from now, and nothing to do the second time`() {
        val left = assertNotNull(stored(SubscriptionStatus.ACTIVE).unsubscribed(now))
        assertEquals(listOf(SubscriptionStatus.UNSUBSCRIBED, now), listOf(left.status, left.unsubscribedAt))
        assertNull(left.unsubscribed(now.plusSeconds(60)))
        assertEquals("NewsletterSubscription(7, ACTIVE)", stored(SubscriptionStatus.ACTIVE).toString())
    }
}
