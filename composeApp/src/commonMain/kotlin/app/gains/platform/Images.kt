package app.gains.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an encoded raster image (WebP, JPEG, PNG) into a bitmap the UI can draw. */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap

/**
 * A picked photo re-encoded as a JPEG no larger than [Photos.MAX_PX] on its long side, so a workout
 * photo is a couple of hundred kilobytes in the database rather than the several megabytes a phone
 * camera produces. Returns the bytes unchanged when they cannot be decoded as an image.
 */
internal expect fun shrinkPhoto(bytes: ByteArray): ByteArray

/** How a workout photo is stored, wherever [shrinkPhoto] is implemented. */
internal object Photos {
    /** The long side of a stored photo: enough for a full-width picture on any phone. */
    const val MAX_PX = 1280

    /** JPEG quality of a stored photo. */
    const val QUALITY = 80
}
