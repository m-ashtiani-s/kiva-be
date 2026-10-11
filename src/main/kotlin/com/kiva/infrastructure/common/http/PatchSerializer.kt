package com.kiva.infrastructure.common.http

import com.kiva.common.patch.Patch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Reads one field of a PATCH body as a [Patch]: a field that is there, `null` included, is [Patch.Set]; a field that
 * isn't there is never read, so its property keeps its default, which must be [Patch.Unchanged]:
 * ```kotlin
 * @Serializable
 * data class UpdateProfileHttpRequest(
 *     @Serializable(with = PatchSerializer::class) val firstName: Patch<String?> = Patch.Unchanged,
 * )
 * ```
 * `{}` leaves the first name, `{"firstName": null}` clears it, `{"firstName": "سارا"}` sets it. Whether null is allowed
 * is the value's serializer's to say: a `Patch<Boolean>` sent as null is an unreadable body (400).
 */
class PatchSerializer<T>(private val valueSerializer: KSerializer<T>) : KSerializer<Patch<T>> {

    override val descriptor: SerialDescriptor = valueSerializer.descriptor

    override fun deserialize(decoder: Decoder): Patch<T> = Patch.Set(valueSerializer.deserialize(decoder))

    // Requests are only read; writing one is a bug, and an unsent field has nothing to write.
    override fun serialize(encoder: Encoder, value: Patch<T>) {
        when (value) {
            Patch.Unchanged -> throw SerializationException("An unchanged field is not written")
            is Patch.Set -> valueSerializer.serialize(encoder, value.value)
        }
    }
}
