package com.kiva.common.railway

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultTest {

    // A tiny value object, shaped like Isbn.makeNew in library-be.
    private data class Sku(val value: String)

    private sealed class SkuFailure : ResultFailure() {
        data object Blank : SkuFailure()
        data class TooLong(val length: Int) : SkuFailure()
    }

    private fun makeSku(raw: String): Result<Sku, SkuFailure> = when {
        raw.isBlank() -> Result.failure(SkuFailure.Blank)
        raw.length > 10 -> Result.failure(SkuFailure.TooLong(raw.length))
        else -> Result.success(Sku(raw.trim()))
    }

    @Nested
    inner class Success {
        private val result = makeSku("AB-12")

        @Test
        fun `holds the value`() {
            val success = assertIs<Result.Success<Sku>>(result)
            assertEquals(Sku("AB-12"), success.value)
        }

        @Test
        fun `reports success`() {
            assertTrue(result.isSuccess)
            assertFalse(result.isFailure)
        }

        @Test
        fun `getOrNull returns the value and failureOrNull returns null`() {
            assertEquals(Sku("AB-12"), result.getOrNull())
            assertNull(result.failureOrNull())
        }
    }

    @Nested
    inner class Failure {
        private val result = makeSku("   ")

        @Test
        fun `holds the failure`() {
            val failure = assertIs<Result.Failure<SkuFailure>>(result)
            assertEquals(SkuFailure.Blank, failure.failure)
        }

        @Test
        fun `reports failure`() {
            assertTrue(result.isFailure)
            assertFalse(result.isSuccess)
        }

        @Test
        fun `getOrNull returns null and failureOrNull returns the failure`() {
            assertNull(result.getOrNull())
            assertEquals(SkuFailure.Blank, result.failureOrNull())
        }

        @Test
        fun `keeps the failure details`() {
            assertEquals(SkuFailure.TooLong(11), makeSku("12345678901").failureOrNull())
        }
    }

    @Nested
    inner class TypeSystem {

        @Test
        fun `success of a subtype fits where the supertype is expected (out T)`() {
            // Compiles only because T is declared `out`.
            val result: Result<Number, SkuFailure> = Result.success(42)
            assertEquals(42, result.getOrNull())
        }

        @Test
        fun `a specific failure fits where the base failure is expected (out F)`() {
            // Compiles only because F is declared `out`.
            val result: Result<Sku, ResultFailure> = makeSku("")
            assertIs<SkuFailure.Blank>(result.failureOrNull())
        }

        @Test
        fun `when over a Result needs no else branch (sealed)`() {
            // Compiles only because Result is sealed: the compiler knows these are the only two cases.
            val message = when (val result = makeSku("AB-12")) {
                is Result.Success -> "sku ${result.value.value}"
                is Result.Failure -> "failed: ${result.failure}"
            }
            assertEquals("sku AB-12", message)
        }
    }

    @Test
    fun `two results with equal contents are equal`() {
        assertEquals(Result.success(Sku("A")), Result.success(Sku("A")))
        assertEquals(Result.failure(SkuFailure.Blank), Result.failure(SkuFailure.Blank))
    }
}
