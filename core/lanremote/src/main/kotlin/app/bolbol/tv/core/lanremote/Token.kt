package app.bolbol.tv.core.lanremote

import java.security.MessageDigest
import java.security.SecureRandom

private val HEX = "0123456789abcdef".toCharArray()

/** 128 random bits as 32 lowercase hex characters. */
internal fun newRemoteToken(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    val chars = CharArray(32)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

/** Constant-time comparison; `false` for a missing candidate. */
internal fun tokenMatches(candidate: String?, expected: String): Boolean {
    if (candidate == null) return false
    return MessageDigest.isEqual(candidate.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}
