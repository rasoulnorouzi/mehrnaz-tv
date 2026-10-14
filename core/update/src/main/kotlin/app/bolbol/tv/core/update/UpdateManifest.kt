package app.bolbol.tv.core.update

import app.bolbol.tv.core.model.AppUpdate
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Where the manifest lives unless a developer override is set (docs/update.md). */
const val DEFAULT_MANIFEST_URL =
    "https://github.com/rasoulnorouzi/mehrnaz-tv/releases/latest/download/update.json"

/**
 * Parses `update.json`, leniently: unknown keys are ignored, numbers may be strings (`"5"`), the SHA-256
 * may be upper-case or have surrounding blanks, `notesFa` may be missing. Anything unusable (not an
 * object, no positive versionCode, no http(s) apkUrl, no 64-hex SHA-256) yields null so the caller can
 * report BAD_MANIFEST instead of offering a download it could not verify.
 */
internal object UpdateManifest {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String): AppUpdate? {
        val obj = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val versionCode = obj.int("versionCode")?.takeIf { it > 0 } ?: return null
        val apkUrl = obj.string("apkUrl")?.trim()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return null
        val sha = obj.string("sha256")?.trim()?.lowercase()
            ?.takeIf { SHA256_HEX.matches(it) } ?: return null
        return AppUpdate(
            versionCode = versionCode,
            versionName = obj.string("versionName")?.trim().orEmpty().ifBlank { versionCode.toString() },
            apkUrl = apkUrl,
            sizeBytes = obj.long("sizeBytes")?.coerceAtLeast(0L) ?: 0L,
            sha256 = sha,
            notesFa = obj.string("notesFa")?.trim().orEmpty(),
        )
    }

    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.string(key: String): String? = primitive(key)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = primitive(key)?.contentOrNull?.trim()?.let {
        it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong()
    }
    private fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

/** True when the published build is newer than the installed one (equal or older: nothing to do). */
internal fun isNewer(published: AppUpdate, installedVersionCode: Int): Boolean =
    published.versionCode > installedVersionCode

/** Lower-case hex SHA-256 of a stream, read to its end (the stream is not closed). */
internal fun sha256Hex(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
    }
    return digest.digest().toHex()
}

internal fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

/** True when [file] exists and its SHA-256 equals [expectedHex] (case-insensitive). */
internal fun verifySha256(file: File, expectedHex: String): Boolean =
    file.isFile && sha256Hex(file).equals(expectedHex.trim(), ignoreCase = true)

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }
