package com.kiva.infrastructure.httpserver.idempotency

import com.kiva.infrastructure.common.http.KivaJson
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** «The same request» for an Idempotency-Key: the same thing asked, however the JSON was written. */
class RequestFingerprintTest {

    @Serializable
    data class Body(
        val addressId: Long? = null,
        val shippingMethod: String,
        val saveNewAddress: Boolean = true,
        val messenger: Messenger? = null,
        val expectedPayable: Long? = null,
    )

    @Serializable
    data class Messenger(val channel: String, val phone: String)

    /** As the route has it: the body decoded from [json], then fingerprinted. */
    private fun fingerprintOf(json: String, path: Map<String, String> = emptyMap()) =
        RequestFingerprint.of(Body.serializer(), KivaJson.decodeFromString(Body.serializer(), json), path)

    @Test
    fun `the order of the keys and the spaces don't change it`() {
        assertEquals(
            fingerprintOf("""{"shippingMethod":"POST","addressId":42,"messenger":{"phone":"09121234567","channel":"TELEGRAM"}}"""),
            fingerprintOf("""{ "messenger": { "channel": "TELEGRAM", "phone": "09121234567" },
                               "addressId": 42,   "shippingMethod": "POST" }"""),
        )
    }

    @Test
    fun `a field left out is the same as the field sent with its default, or as null`() {
        val short = fingerprintOf("""{"shippingMethod":"POST"}""")

        assertEquals(short, fingerprintOf("""{"shippingMethod":"POST","saveNewAddress":true}"""))
        assertEquals(short, fingerprintOf("""{"shippingMethod":"POST","addressId":null,"expectedPayable":null}"""))
    }

    @Test
    fun `any other value is another request`() {
        val asked = fingerprintOf("""{"shippingMethod":"POST","expectedPayable":1525000}""")

        assertNotEquals(asked, fingerprintOf("""{"shippingMethod":"TIPAX","expectedPayable":1525000}"""))
        assertNotEquals(asked, fingerprintOf("""{"shippingMethod":"POST","expectedPayable":1525001}"""))
        assertNotEquals(asked, fingerprintOf("""{"shippingMethod":"POST","expectedPayable":1525000,"saveNewAddress":false}"""))
    }

    @Test
    fun `the path is part of the request - a retry of another payment with the same body is another request`() {
        val body = """{"shippingMethod":"POST"}"""

        assertEquals(fingerprintOf(body, mapOf("paymentId" to "pay_0000000000A1")), fingerprintOf(body, mapOf("paymentId" to "pay_0000000000A1")))
        assertNotEquals(fingerprintOf(body, mapOf("paymentId" to "pay_0000000000A1")), fingerprintOf(body, mapOf("paymentId" to "pay_0000000000B2")))
    }

    @Test
    fun `the canonical form is the body and the path, keys sorted at every level, no spaces`() {
        val canonical = RequestFingerprint.canonicalFormOf(
            KivaJson.parseToJsonElement("""{ "b": 1, "a": { "y": [ {"d": 2, "c": 3} ], "x": "س" } }"""),
            mapOf("paymentId" to "pay_0000000000A1"),
        )

        assertEquals("""{"body":{"a":{"x":"س","y":[{"c":3,"d":2}]},"b":1},"path":{"paymentId":"pay_0000000000A1"}}""", canonical)
    }
}
