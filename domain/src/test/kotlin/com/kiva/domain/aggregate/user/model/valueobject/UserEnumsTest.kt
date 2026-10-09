package com.kiva.domain.aggregate.user.model.valueobject

import com.kiva.common.railway.Result
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The contract's enums of a user, read from the raw text a request carries. */
class UserEnumsTest {

    @Test
    fun `the values are exactly the contract's`() {
        assertEquals(listOf("FEMALE", "MALE", "UNSPECIFIED"), Gender.entries.map { it.name })
        assertEquals(listOf("RUBIKA", "TELEGRAM", "BALE"), MessengerChannel.entries.map { it.name })
        assertEquals(
            listOf("CUSTOMER", "ADMIN", "CATALOG_MANAGER", "OPERATOR", "CONTENT_EDITOR", "SUPPORT"),
            Role.entries.map { it.name },
        )
    }

    @Test
    fun `every name of the contract is read as it is`() {
        Gender.entries.forEach { assertEquals(Result.success(it), Gender.makeNew(it.name)) }
        MessengerChannel.entries.forEach { assertEquals(Result.success(it), MessengerChannel.makeNew(it.name)) }
        Role.entries.forEach { assertEquals(Result.success(it), Role.makeNew(it.name)) }
    }

    // Exactly as sent: a lowercase or padded name is a client bug worth seeing, not something to guess.
    @ParameterizedTest
    @ValueSource(strings = ["", "female", " ADMIN", "WHATSAPP", "OWNER"])
    fun `anything else is refused`(raw: String) {
        assertEquals(Result.failure(Gender.Failure.Invalid), Gender.makeNew(raw))
        assertEquals(Result.failure(MessengerChannel.Failure.Invalid), MessengerChannel.makeNew(raw))
        assertEquals(Result.failure(Role.Failure.Invalid), Role.makeNew(raw))
    }

    @Test
    fun `each failure has its own code, and names its field only when it always comes in the same one`() {
        assertEquals("GENDER_INVALID" to "gender", Gender.Failure.Invalid.let { it.code to it.field })
        assertEquals("MESSENGER_CHANNEL_INVALID", MessengerChannel.Failure.Invalid.code)
        assertNull(MessengerChannel.Failure.Invalid.field)
        assertEquals("ROLE_INVALID", Role.Failure.Invalid.code)
        assertNull(Role.Failure.Invalid.field)
    }
}
