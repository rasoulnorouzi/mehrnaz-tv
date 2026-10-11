@file:OptIn(UnstableApi::class)

package app.bolbol.tv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

/** A playback failure, reduced to what the UI needs to pick a message and an action. */
data class PlaybackError(
    val kind: Kind,
    /** PlaybackException.errorCode, for logs and the debug overlay. */
    val errorCode: Int,
    /** HTTP status when the CDN answered with an error status, else null. */
    val httpStatus: Int?,
    /** e.g. "ERROR_CODE_IO_BAD_HTTP_STATUS". */
    val codeName: String,
) {
    enum class Kind {
        /** No connection, DNS, timeout, reset: "check the internet", retry helps. */
        NETWORK,

        /** 4xx from the CDN: this file is gone/forbidden; another quality/mirror may work. */
        SOURCE_4XX,

        /** 5xx from the CDN: server trouble, retry later or another quality. */
        SOURCE_5XX,

        /** The file downloaded but is malformed/unsupported. */
        SOURCE_FORMAT,

        /** The TV can't decode it (codec init/decoding failed): a lower quality usually works. */
        DECODER,

        OTHER,
    }

    companion object {
        /** Pure classification from Media3's error code and the HTTP status (unit-tested). */
        fun classify(errorCode: Int, httpStatus: Int?): Kind = when {
            httpStatus != null && httpStatus in 400..499 -> Kind.SOURCE_4XX
            httpStatus != null && httpStatus in 500..599 -> Kind.SOURCE_5XX
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> Kind.SOURCE_4XX
            errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
                errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> Kind.SOURCE_FORMAT
            errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> Kind.NETWORK
            errorCode in 2000..2999 -> Kind.NETWORK
            errorCode in 3000..3999 -> Kind.SOURCE_FORMAT
            errorCode in 4000..4999 || errorCode in 5000..5999 -> Kind.DECODER
            else -> Kind.OTHER
        }

        fun from(e: PlaybackException): PlaybackError {
            val status = generateSequence<Throwable>(e) { it.cause }
                .take(8)
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()
                ?.responseCode
            return PlaybackError(classify(e.errorCode, status), e.errorCode, status, e.errorCodeName)
        }
    }
}
