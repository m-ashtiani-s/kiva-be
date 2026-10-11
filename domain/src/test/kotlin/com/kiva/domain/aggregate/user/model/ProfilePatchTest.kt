package com.kiva.domain.aggregate.user.model

import com.kiva.common.patch.Patch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ProfilePatchTest {

    @Test
    fun `toString names the fields that were sent and none of their values`() {
        val patch = ProfilePatch(
            firstName = Patch.Set("سارا"),
            email = Patch.Set(null),
            defaultMessengerPhone = Patch.Set("09121234567"),
        )

        assertEquals("ProfilePatch(sent=[firstName, email, defaultMessengerPhone])", patch.toString())
    }
}
