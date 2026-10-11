package com.kiva.infrastructure.common.http

import com.kiva.common.patch.Patch
import com.kiva.infrastructure.httpserver.controller.httprest.model.request.UpdateProfileHttpRequest
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A PATCH body read with the API's own JSON setup: a field left out and a field sent as null are told apart. */
class PatchSerializerTest {

    private fun read(json: String) = KivaJson.decodeFromString(UpdateProfileHttpRequest.serializer(), json)

    @Test
    fun `an empty body changes nothing`() {
        assertEquals(UpdateProfileHttpRequest(), read("{}"))
        assertEquals(Patch.Unchanged, read("{}").firstName)
    }

    @Test
    fun `a field sent as null clears it, a field sent with a value sets it, the others stay unchanged`() {
        val request = read("""{"firstName": null, "lastName": "محمدی", "marketingSmsOptIn": true}""")

        assertEquals(Patch.Set(null), request.firstName)
        assertEquals(Patch.Set("محمدی"), request.lastName)
        assertEquals(Patch.Set(true), request.marketingSmsOptIn)
        assertEquals(Patch.Unchanged, request.email)
        assertEquals(Patch.Unchanged, request.gender)
    }

    @Test
    fun `phone and roles are not part of the request, so sending them changes nothing`() {
        assertEquals(UpdateProfileHttpRequest(), read("""{"phone": "09351112233", "roles": ["ADMIN"], "id": 1}"""))
    }

    @Test
    fun `null for a field that can't be cleared, or a value of the wrong kind, is an unreadable body`() {
        assertFailsWith<SerializationException> { read("""{"gender": null}""") }
        assertFailsWith<SerializationException> { read("""{"marketingSmsOptIn": null}""") }
        assertFailsWith<SerializationException> { read("""{"firstName": 5}""") }
        assertFailsWith<SerializationException> { read("""{"marketingSmsOptIn": "yes"}""") }
    }
}
