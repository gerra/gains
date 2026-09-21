package app.gains.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Decodes an encoded raster image (WebP, JPEG, PNG) into a bitmap the UI can draw. */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap
