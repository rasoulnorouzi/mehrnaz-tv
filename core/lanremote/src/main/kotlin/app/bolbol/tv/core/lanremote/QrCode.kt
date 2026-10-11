package app.bolbol.tv.core.lanremote

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Square grid of QR modules (true = dark), quiet zone included. Pure data, no Android types. */
internal class QrMatrix(val size: Int, private val dark: BooleanArray) {
    operator fun get(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && dark[y * size + x]

    /** Black-on-white ARGB pixels, each module drawn as a [scale] x [scale] square; row-major, `size * scale` wide. */
    fun toArgb(scale: Int): IntArray {
        val side = size * scale
        val pixels = IntArray(side * side)
        for (py in 0 until side) {
            val row = (py / scale) * size
            for (px in 0 until side) {
                pixels[py * side + px] = if (dark[row + px / scale]) BLACK else WHITE
            }
        }
        return pixels
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}

/** The QR spec asks for 4 modules of empty border; scanners struggle on a dark TV background without it. */
internal const val QR_QUIET_ZONE_MODULES = 4

/** Encodes [content] (UTF-8, error correction M) as a [QrMatrix] with [quietZone] white modules around it. */
@Throws(WriterException::class)
internal fun encodeQr(content: String, quietZone: Int = QR_QUIET_ZONE_MODULES): QrMatrix {
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
        EncodeHintType.MARGIN to 0, // we add the quiet zone ourselves, so the module count stays exact
    )
    val bits = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
    val modules = bits.width
    val size = modules + 2 * quietZone
    val dark = BooleanArray(size * size)
    for (y in 0 until modules) {
        for (x in 0 until modules) {
            if (bits.get(x, y)) dark[(y + quietZone) * size + x + quietZone] = true
        }
    }
    return QrMatrix(size, dark)
}

/**
 * QR code of [content], black on white with a quiet zone, in a white rounded square of [sizeDp].
 * The bitmap is built once per (content, pixel size) with whole pixels per module and drawn without filtering,
 * so modules stay crisp for the phone camera. Shows only the white square if [content] cannot be encoded.
 */
@Composable
fun QrCode(content: String, sizeDp: Dp, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.roundToPx() }
    val image = remember(content, sizePx) { renderQrImage(content, sizePx) }
    Box(
        modifier = modifier.size(sizeDp).background(Color.White, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = "کد QR برای تایپ با گوشی",
                modifier = Modifier.size(with(density) { image.width.toDp() }),
                filterQuality = FilterQuality.None,
            )
        }
    }
}

private fun renderQrImage(content: String, sizePx: Int): ImageBitmap? {
    val matrix = try {
        encodeQr(content)
    } catch (_: WriterException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    val scale = (sizePx / matrix.size).coerceAtLeast(1)
    val side = matrix.size * scale
    return Bitmap.createBitmap(matrix.toArgb(scale), side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
}
