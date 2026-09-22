package app.gains.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap =
    (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not an image")).asImageBitmap()

internal actual fun shrinkPhoto(bytes: ByteArray): ByteArray {
    // Two passes: the bounds first, so a camera-sized photo is never decoded whole into memory.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) return bytes
    val options = BitmapFactory.Options().apply {
        var sample = 1
        while (longest / (sample * 2) >= Photos.MAX_PX) sample *= 2
        inSampleSize = sample
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return bytes
    val scale = Photos.MAX_PX.toFloat() / maxOf(decoded.width, decoded.height)
    val scaled = if (scale >= 1f) decoded else Bitmap.createScaledBitmap(
        decoded,
        (decoded.width * scale).toInt().coerceAtLeast(1),
        (decoded.height * scale).toInt().coerceAtLeast(1),
        true,
    )
    return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, Photos.QUALITY, it) }.toByteArray()
}
