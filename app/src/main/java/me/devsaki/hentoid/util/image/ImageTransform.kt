package me.devsaki.hentoid.util.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.Build
import com.squareup.moshi.JsonClass
import me.devsaki.hentoid.core.HentoidApp
import me.devsaki.hentoid.enums.PictureEncoder
import me.devsaki.hentoid.util.getScreenDimensionsPx
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale

@JsonClass(generateAdapter = true)
data class TransformParams(
    val resizeEnabled: Boolean,
    val resizeMethod: Int,
    val resize1Ratio: Int,
    val resize2Height: Int,
    val resize2Width: Int,
    val resize3Ratio: Int,
    val transcodeMethod: Int,
    val transcoderAll: PictureEncoder,
    val transcoderLossy: PictureEncoder,
    val transcoderLossless: PictureEncoder,
    val transcodeQuality: Int,
    @Transient var forceManhwa: Boolean = false
)

val screenWidth = getScreenDimensionsPx(HentoidApp.getInstance()).x
val screenHeight = getScreenDimensionsPx(HentoidApp.getInstance()).y

private const val MAX_WEBP_DIMENSION = 16383 // As per WEBP specifications

/**
 * Transform the given raw picture data using the given params
 */
fun transform(
    source: ByteArray,
    params: TransformParams,
    allowBogusAiRescale: Boolean = false
): ByteArray {
    if (isImageAnimated(source)) return source

    val options = BitmapFactory.Options()
    options.inJustDecodeBounds = true
    BitmapFactory.decodeByteArray(source, 0, source.size, options)
    val dims = Point(options.outWidth, options.outHeight)
    val bitmapOut: Bitmap = if (params.resizeEnabled) {
        when (params.resizeMethod) {
            0 -> resizeScreenRatio(source, dims, params.resize1Ratio / 100f)
            1 -> resizeDims(
                source, dims, params.resize2Height, params.resize2Width, params.forceManhwa
            )

            2 -> resizePlainRatio(source, dims, params.resize3Ratio / 100f)
            3 -> { // AI rescale; handled at Worker level
                val scale = if (allowBogusAiRescale) 2f else 1f
                resizePlainRatio(source, dims, scale, allowBogusAiRescale)
            }

            else -> resizePlainRatio(source, dims, 1f)
        }
    } else {
        BitmapFactory.decodeByteArray(source, 0, source.size)
    }

    val isLossless = isImageLossless(source)
    val targetDims = Point(bitmapOut.width, bitmapOut.height)
    try {
        return transcodeTo(
            bitmapOut, determineEncoder(isLossless, targetDims, params), params.transcodeQuality
        )
    } finally {
        bitmapOut.recycle()
    }
}

private fun resizeScreenRatio(source: ByteArray, dims: Point, ratio: Float): Bitmap {
    val targetWidth = screenWidth * ratio
    val targetHeight = screenHeight * ratio
    val widthRatio = targetWidth / dims.x
    val heightRatio = targetHeight / dims.y
    val targetRatio = if (widthRatio > 1 && heightRatio > 1) max(widthRatio, heightRatio)
    else min(widthRatio, heightRatio)
    return resizePlainRatio(source, dims, targetRatio)
}

private fun resizeDims(
    source: ByteArray, dims: Point, maxHeight: Int, maxWidth: Int, forceManhwa: Boolean
): Bitmap {
    val isManhwa = forceManhwa || (dims.y * 1.0 / dims.x > 3)
    val ratio = if (isManhwa) {
        if (dims.x > maxWidth) maxWidth * 1f / dims.x else 1f
    } else {
        val maxDim = max(dims.x, dims.y) // Portrait vs. landscape
        if (maxDim > maxHeight) maxHeight * 1f / maxDim else 1f
    }
    return resizePlainRatio(source, dims, ratio)
}

private fun resizePlainRatio(
    source: ByteArray,
    dims: Point,
    ratio: Float,
    allowUpscale: Boolean = false
): Bitmap {
    val sourceBmp = BitmapFactory.decodeByteArray(source, 0, source.size)
    return if (ratio > 0.99 && ratio < 1.01) sourceBmp // Don't do anything
    else if (ratio > 1.01 && !allowUpscale) sourceBmp // Prevent upscaling
    else {
        val rescaled = sharpRescale(sourceBmp, ratio)
        if (rescaled != sourceBmp) sourceBmp.recycle()
        try {
            rescaled.scale((dims.x * ratio).toInt(), (dims.y * ratio).toInt())
        } finally {
            rescaled.recycle()
        }
    }
}

fun determineEncoder(isLossless: Boolean, dims: Point, params: TransformParams): PictureEncoder {
    // AI rescale always produces PNGs
    if (3 == params.resizeMethod) return PictureEncoder.PNG

    // Other cases
    val result = when (params.transcodeMethod) {
        0 -> params.transcoderAll
        else -> if (isLossless) params.transcoderLossless else params.transcoderLossy
    }
    return if (PictureEncoder.WEBP_LOSSY == result && max(dims.x, dims.y) > MAX_WEBP_DIMENSION)
        PictureEncoder.JPEG
    else if (PictureEncoder.WEBP_LOSSLESS == result && max(dims.x, dims.y) > MAX_WEBP_DIMENSION)
        PictureEncoder.PNG
    else result
}

@Suppress("DEPRECATION")
fun transcodeTo(bitmap: Bitmap, encoder: PictureEncoder, quality: Int): ByteArray {
    val output = ByteArrayOutputStream()
    when (encoder) {
        PictureEncoder.WEBP_LOSSY -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bitmap.compress(
            Bitmap.CompressFormat.WEBP_LOSSY,
            quality,
            output
        )
        else bitmap.compress(Bitmap.CompressFormat.WEBP, quality, output)

        PictureEncoder.WEBP_LOSSLESS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bitmap.compress(
            Bitmap.CompressFormat.WEBP_LOSSLESS,
            100,
            output
        )
        else bitmap.compress(Bitmap.CompressFormat.WEBP, 100, output)

        PictureEncoder.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)

        PictureEncoder.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    }
    return output.toByteArray()
}