package com.kiva.common.railway

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ResultOperationsTest {

    private sealed class TestFailure(code: String) : ResultFailure(code, FailureKind.INVALID) {
        data object Blank : TestFailure("BLANK")
        data class NotANumber(val raw: String) : TestFailure("NOT_A_NUMBER")
        data class NotPositive(val value: Int) : TestFailure("NOT_POSITIVE")
    }

    private data class Wrapped(val inner: TestFailure) :
        ResultFailure("WRAPPED", FailureKind.INVALID, causes = listOf(inner))

    private fun parse(raw: String): Result<Int, TestFailure> = when {
        raw.isBlank() -> Result.failure(TestFailure.Blank)
        raw.toIntOrNull() == null -> Result.failure(TestFailure.NotANumber(raw))
        else -> Result.success(raw.toInt())
    }

    private fun requirePositive(value: Int): Result<Int, TestFailure> =
        if (value > 0) Result.success(value) else Result.failure(TestFailure.NotPositive(value))

    private val ok: Result<Int, TestFailure> = Result.success(5)
    private val failed: Result<Int, TestFailure> = Result.failure(TestFailure.Blank)

    @Nested
    inner class MapOnSuccess {
        @Test
        fun `transforms the value of a success`() {
            assertEquals(Result.success(10), ok.mapOnSuccess { it * 2 })
        }

        @Test
        fun `does not run on a failure`() {
            var calls = 0
            val result = failed.mapOnSuccess { calls++; it * 2 }
            assertEquals(failed, result)
            assertEquals(0, calls)
        }
    }

    @Nested
    inner class MapOnFailure {
        @Test
        fun `transforms the failure`() {
            assertEquals(Result.failure(Wrapped(TestFailure.Blank)), failed.mapOnFailure { Wrapped(it) })
        }

        @Test
        fun `does not run on a success`() {
            var calls = 0
            val result = ok.mapOnFailure { calls++; Wrapped(it) }
            assertEquals(5, result.getOrNull())
            assertEquals(0, calls)
        }
    }

    @Nested
    inner class Bind {
        @Test
        fun `chains a step that can fail`() {
            assertEquals(Result.success(7), parse("7").bind { requirePositive(it) })
            assertEquals(Result.failure(TestFailure.NotPositive(-3)), parse("-3").bind { requirePositive(it) })
        }

        @Test
        fun `stops at the first failure`() {
            var laterSteps = 0
            val result = parse("abc")
                .bind { laterSteps++; requirePositive(it) }
                .bind { laterSteps++; Result.success(it * 2) }

            assertEquals(Result.failure(TestFailure.NotANumber("abc")), result)
            assertEquals(0, laterSteps)
        }

        @Test
        fun `mapOnSuccess with a failing step would nest results, bind flattens them`() {
            val nested: Result<Result<Int, TestFailure>, TestFailure> = parse("7").mapOnSuccess { requirePositive(it) }
            val flat: Result<Int, TestFailure> = parse("7").bind { requirePositive(it) }

            assertEquals(Result.success(Result.success(7)), nested)
            assertEquals(Result.success(7), flat)
        }
    }

    @Nested
    inner class OnSuccessAndOnFailure {
        @Test
        fun `onSuccess runs only on success and returns the same result`() {
            val seen = mutableListOf<Int>()
            assertEquals(ok, ok.onSuccess { seen += it })
            assertEquals(failed, failed.onSuccess { seen += it })
            assertEquals(listOf(5), seen)
        }

        @Test
        fun `onFailure runs only on failure and returns the same result`() {
            val seen = mutableListOf<TestFailure>()
            assertEquals(ok, ok.onFailure { seen += it })
            assertEquals(failed, failed.onFailure { seen += it })
            assertEquals(listOf<TestFailure>(TestFailure.Blank), seen)
        }
    }

    @Nested
    inner class Fold {
        @Test
        fun `turns both branches into one value`() {
            val describe = { r: Result<Int, TestFailure> -> r.fold({ "value $it" }, { "failed $it" }) }
            assertEquals("value 5", describe(ok))
            assertEquals("failed Blank", describe(failed))
        }
    }

    @Nested
    inner class UnwrapOr {
        // The same shape a use case has: unwrap each step, leave early on failure.
        private fun doubled(raw: String): Result<Int, TestFailure> {
            val number = parse(raw).unwrapOr { return Result.failure(it) }
            val positive = requirePositive(number).unwrapOr { return Result.failure(it) }
            return Result.success(positive * 2)
        }

        @Test
        fun `returns the plain value on success`() {
            assertEquals(Result.success(14), doubled("7"))
        }

        @Test
        fun `leaves the calling function on failure`() {
            assertEquals(Result.failure(TestFailure.NotANumber("x")), doubled("x"))
            assertEquals(Result.failure(TestFailure.NotPositive(0)), doubled("0"))
        }

        @Test
        fun `can also throw instead of returning`() {
            val value = runCatching { failed.unwrapOr { throw IllegalStateException("boom") } }
            assertNull(value.getOrNull())
            assertEquals("boom", value.exceptionOrNull()?.message)
        }
    }
}
