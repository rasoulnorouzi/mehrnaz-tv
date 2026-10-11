package app.bolbol.tv.core.network.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

/*
 * Custom serializers for the API's PHP-isms (docs/api.md quirks 2, 3, 5).
 * All of them are total: whatever shape arrives, they produce a value instead of throwing.
 */

private fun JsonDecoder.primitiveOrNull(): JsonPrimitive? =
    (decodeJsonElement() as? JsonPrimitive)?.takeUnless { it is JsonNull }

/** `"2025"`, `2025`, `true`, `null`, `[]` -> String ("" when absent or not a primitive). */
internal object LenientStringSerializer : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        (decoder as JsonDecoder).primitiveOrNull()?.content.orEmpty()

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** `12`, `"12"`, `12.0`, `""`, `null` -> Long (0 when absent or unparsable). */
internal object LenientLongSerializer : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)
    override fun deserialize(decoder: Decoder): Long {
        val content = (decoder as JsonDecoder).primitiveOrNull()?.content?.trim().orEmpty()
        return content.toLongOrNull() ?: content.toDoubleOrNull()?.toLong() ?: 0L
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** `12`, `"12"`, `""`, `null` -> Int (0 when absent or unparsable). */
internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)
    override fun deserialize(decoder: Decoder): Int =
        LenientLongSerializer.deserialize(decoder).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

/** Flags: `true`, `"on"`, `"1"`, `"true"`, `1` -> true; `""`, `false`, `null`, anything else -> false. */
internal object FlagSerializer : KSerializer<Boolean> {
    override val descriptor = PrimitiveSerialDescriptor("Flag", PrimitiveKind.BOOLEAN)
    override fun deserialize(decoder: Decoder): Boolean {
        val content = (decoder as JsonDecoder).primitiveOrNull()?.content?.trim()?.lowercase().orEmpty()
        return content == "on" || content == "1" || content == "true" || content == "yes"
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/** Key injected by [MapOrListSerializer] with `withKeys = true`, so movie player/download entries can be joined. */
internal const val KEY_FIELD = "_key"

/**
 * PHP array/object duality: an object keyed by (numeric) strings, an array, `[]`, `""` or `null`
 * all become a `List<T>`. Object entries are ordered by numeric key.
 *
 * With [withKeys] each element object also receives its original key (array index for arrays) as
 * [KEY_FIELD]; the movie `download_links` and `player_links` share keys and are joined on it.
 */
internal open class MapOrListSerializer<T>(
    element: KSerializer<T>,
    private val withKeys: Boolean = false,
) : JsonTransformingSerializer<List<T>>(ListSerializer(element)) {

    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> if (withKeys) {
            JsonArray(element.mapIndexed { index, value -> value.withKey(index.toString()) })
        } else {
            element
        }

        is JsonObject -> JsonArray(
            element.entries
                .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
                .map { (key, value) -> if (withKeys) value.withKey(key) else value },
        )

        else -> JsonArray(emptyList())
    }

    private fun JsonElement.withKey(key: String): JsonElement =
        if (this is JsonObject) JsonObject(this + (KEY_FIELD to JsonPrimitive(key))) else this
}
