package com.kiva.common.railway

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ZipTest {

    // Two value objects of a shipping address form, each with its own failure.
    private data class PostalCode(val value: String)
    private data class Mobile(val value: String)
    private data class City(val value: String)
    private data class Recipient(val value: String)

    private data object InvalidPostalCode : ResultFailure("POSTAL_CODE_INVALID", FailureKind.INVALID, field = "postalCode")
    private data object InvalidMobile : ResultFailure("MOBILE_INVALID", FailureKind.INVALID, field = "mobile")
    private data object BlankCity : ResultFailure("CITY_BLANK", FailureKind.INVALID, field = "city")
    private data object BlankRecipient : ResultFailure("RECIPIENT_BLANK", FailureKind.INVALID, field = "recipient")

    private data class InvalidAddress(val reasons: List<ResultFailure>) :
        ResultFailure("INVALID_ADDRESS", FailureKind.INVALID, causes = reasons)

    private data class Address(val postalCode: PostalCode, val mobile: Mobile)

    private fun postalCode(raw: String): Result<PostalCode, InvalidPostalCode> =
        if (raw.length == 10 && raw.all(Char::isDigit)) Result.success(PostalCode(raw)) else Result.failure(InvalidPostalCode)

    private fun mobile(raw: String): Result<Mobile, InvalidMobile> =
        if (raw.startsWith("09") && raw.length == 11) Result.success(Mobile(raw)) else Result.failure(InvalidMobile)

    private fun city(raw: String): Result<City, BlankCity> =
        if (raw.isNotBlank()) Result.success(City(raw)) else Result.failure(BlankCity)

    private fun recipient(raw: String): Result<Recipient, BlankRecipient> =
        if (raw.isNotBlank()) Result.success(Recipient(raw)) else Result.failure(BlankRecipient)

    private fun address(rawPostalCode: String, rawMobile: String): Result<Address, InvalidAddress> =
        Result.zip(postalCode(rawPostalCode), mobile(rawMobile), ::InvalidAddress) { p, m -> Address(p, m) }

    @Nested
    inner class TwoInputs {
        @Test
        fun `combines the values when all succeed`() {
            assertEquals(
                Result.success(Address(PostalCode("1234567890"), Mobile("09121234567"))),
                address("1234567890", "09121234567"),
            )
        }

        @Test
        fun `reports a single failure as one cause`() {
            val failure = address("123", "09121234567").failureOrNull()
            assertEquals(listOf<ResultFailure>(InvalidPostalCode), failure?.causes)
        }

        @Test
        fun `collects every failure, not just the first`() {
            val failure = address("123", "123").failureOrNull()

            assertEquals("INVALID_ADDRESS", failure?.code)
            assertEquals(listOf("POSTAL_CODE_INVALID", "MOBILE_INVALID"), failure?.causes?.map { it.code })
            assertEquals(listOf("postalCode", "mobile"), failure?.causes?.map { it.field })
        }

        @Test
        fun `does not call combine when something failed`() {
            var calls = 0
            Result.zip(postalCode("x"), mobile("09121234567"), ::InvalidAddress) { p, m -> calls++; Address(p, m) }
            assertEquals(0, calls)
        }
    }

    @Nested
    inner class ThreeInputs {
        @Test
        fun `combines the values when all succeed`() {
            val result = Result.zip(postalCode("1234567890"), mobile("09121234567"), city("Tehran"), ::InvalidAddress) { p, m, c ->
                "${c.value} ${p.value} ${m.value}"
            }
            assertEquals(Result.success("Tehran 1234567890 09121234567"), result)
        }

        @Test
        fun `collects every failure in input order`() {
            val result = Result.zip(postalCode("x"), mobile("09121234567"), city(" "), ::InvalidAddress) { _, _, _ -> Unit }

            val failure = assertIs<Result.Failure<InvalidAddress>>(result).failure
            assertEquals(listOf("POSTAL_CODE_INVALID", "CITY_BLANK"), failure.causes.map { it.code })
        }
    }

    @Nested
    inner class FourInputs {
        @Test
        fun `combines the values when all succeed`() {
            val result = Result.zip(postalCode("1234567890"), mobile("09121234567"), city("Tehran"), recipient("Sara"), ::InvalidAddress) { p, m, c, r ->
                "${r.value}, ${c.value} ${p.value} ${m.value}"
            }
            assertEquals(Result.success("Sara, Tehran 1234567890 09121234567"), result)
        }

        @Test
        fun `collects every failure in input order`() {
            val result = Result.zip(postalCode("x"), mobile("123"), city("Tehran"), recipient(""), ::InvalidAddress) { _, _, _, _ -> Unit }

            val failure = assertIs<Result.Failure<InvalidAddress>>(result).failure
            assertEquals(listOf("POSTAL_CODE_INVALID", "MOBILE_INVALID", "RECIPIENT_BLANK"), failure.causes.map { it.code })
        }
    }

    @Nested
    inner class AList {
        @Test
        fun `gives every value in order when all succeed`() {
            val result = Result.zipAll(listOf(city("Tehran"), city("Shiraz"), city("Rasht")), ::InvalidAddress)

            assertEquals(Result.success(listOf(City("Tehran"), City("Shiraz"), City("Rasht"))), result)
        }

        @Test
        fun `collects every failure in input order`() {
            val result = Result.zipAll(listOf(postalCode("x"), postalCode("1234567890"), postalCode("y")), ::InvalidAddress)

            val failure = assertIs<Result.Failure<InvalidAddress>>(result).failure
            assertEquals(listOf<ResultFailure>(InvalidPostalCode, InvalidPostalCode), failure.causes)
        }

        @Test
        fun `an empty list is an empty success`() {
            assertEquals(Result.success(emptyList()), Result.zipAll(emptyList<Result<City, ResultFailure>>(), ::InvalidAddress))
        }
    }
}
