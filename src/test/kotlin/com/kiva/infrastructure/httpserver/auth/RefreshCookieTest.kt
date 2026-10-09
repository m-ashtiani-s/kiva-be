package com.kiva.infrastructure.httpserver.auth

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RefreshCookieTest {

    @Test
    fun `the cookie is HttpOnly, Secure, SameSite=Strict and only for the login endpoints, as the contract writes it`() {
        assertEquals(
            "kiva_rt=abc-DEF_123; Max-Age=2592000; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict",
            RefreshCookie.header("abc-DEF_123", Duration.ofDays(30)),
        )
    }

    @Test
    fun `clearing it keeps the same path and attributes, so the browser drops that very cookie`() {
        assertEquals("kiva_rt=; Max-Age=0; Path=/api/v1/auth; Secure; HttpOnly; SameSite=Strict", RefreshCookie.clearingHeader())
    }

    @Test
    fun `a value that could end the cookie or add an attribute is refused`() {
        assertFailsWith<IllegalArgumentException> { RefreshCookie.header("abc; Domain=evil.example", Duration.ofDays(30)) }
        assertFailsWith<IllegalArgumentException> { RefreshCookie.header("", Duration.ofDays(30)) }
        assertFailsWith<IllegalArgumentException> { RefreshCookie.header("abc", Duration.ofSeconds(-1)) }
    }
}
