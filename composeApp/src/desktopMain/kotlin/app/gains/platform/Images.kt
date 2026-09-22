package app.gains.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap = Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual fun shrinkPhoto(bytes: ByteArray): ByteArray = skiaShrink(bytes)

/**
 * Skia does the work on both platforms that draw with it. Kept beside [decodeImage], which is the
 * same code on the desktop and on iOS for the same reason.
 */
private fun skiaShrink(bytes: ByteArray): ByteArray {
    val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return bytes
    val longest = maxOf(image.width, image.height)
    val scale = if (longest > Photos.MAX_PX) Photos.MAX_PX.toFloat() / longest else 1f
    val width = (image.width * scale).toInt().coerceAtLeast(1)
    val height = (image.height * scale).toInt().coerceAtLeast(1)
    val surface = Surface.makeRasterN32Premul(width, height)
    surface.canvas.drawImageRect(
        image,
        Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        Rect.makeWH(width.toFloat(), height.toFloat()),
        SamplingMode.LINEAR,
        null,
        true,
    )
    val encoded = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, Photos.QUALITY) ?: return bytes
    return encoded.bytes
}
