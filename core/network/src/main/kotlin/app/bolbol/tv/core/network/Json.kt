package app.bolbol.tv.core.network

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Lenient JSON configuration for the Bolboljan API (see docs/api.md section 8.1).
 *
 * The server freely adds/renames fields, mixes numbers and strings, and sends `null` or `""` for
 * missing values, so every DTO property has a default and unknown keys are ignored.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val BolboljanJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    explicitNulls = false
}
