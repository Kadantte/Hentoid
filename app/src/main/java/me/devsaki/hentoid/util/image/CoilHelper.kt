package me.devsaki.hentoid.util.image

import android.content.Context
import android.view.View
import android.widget.ImageView
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.target
import coil3.serviceLoaderEnabled
import com.github.penfeizhou.animation.apng.APNGDrawable
import com.github.penfeizhou.animation.io.ByteBufferReader
import me.devsaki.hentoid.core.HentoidApp
import me.devsaki.hentoid.database.domains.Content
import me.devsaki.hentoid.util.getContentHeaders
import okio.BufferedSource
import java.io.InputStream
import java.nio.ByteBuffer

private val stillImageLoader: ImageLoader by lazy { initStillImageLoader() }

fun clearCoilCache(context: Context, memory: Boolean = true, file: Boolean = true) {
    val imageLoader = context.imageLoader
    if (memory) imageLoader.memoryCache?.clear()
    if (file) imageLoader.diskCache?.clear()
}

private fun initStillImageLoader(): ImageLoader {
    return ImageLoader.Builder(HentoidApp.getInstance()).serviceLoaderEnabled(false).build()
}

fun ImageView.loadStill(data: Any) {
    val request = ImageRequest.Builder(context)
        .data(data)
        .target(this)

    stillImageLoader.enqueue(request.build())
}

fun ImageView.loadCover(content: Content, disableAnimation: Boolean = false) {
    val thumbLocation = content.cover.usableUri
    if (thumbLocation.isEmpty()) {
        this.visibility = View.INVISIBLE
        return
    }
    this.visibility = View.VISIBLE

    // Use content's cookies to load image (useful for ExHentai when viewing queue screen)
    val networkHeaders = if (thumbLocation.startsWith("http")) {
        val headers = NetworkHeaders.Builder()
        getContentHeaders(content).forEach {
            headers.add(it.first, it.second)
        }
        headers.build()
    } else {
        NetworkHeaders.EMPTY
    }

    val request = ImageRequest.Builder(context)
        .data(thumbLocation)
        .target(this)
        .httpHeaders(networkHeaders)

    val loader = if (disableAnimation) stillImageLoader
    else SingletonImageLoader.get(this.context)
    loader.enqueue(request.build())
}

class AnimatedPngDecoder(private val source: ImageSource) : Decoder {

    override suspend fun decode(): DecodeResult {
        // We must buffer the source into memory as APNGDrawable decodes
        // the image lazily at draw time which is prohibited by Coil.
        val buffer = source.source().squashToDirectByteBuffer()
        return DecodeResult(
            image = APNGDrawable { ByteBufferReader(buffer) }.asImage(),
            isSampled = false,
        )
    }

    private fun BufferedSource.squashToDirectByteBuffer(): ByteBuffer {
        // Squash bytes to BufferedSource inner buffer then we know total byteCount.
        request(Long.MAX_VALUE)

        val byteBuffer = ByteBuffer.allocateDirect(buffer.size.toInt())
        while (!buffer.exhausted()) buffer.read(byteBuffer)
        byteBuffer.flip()
        return byteBuffer
    }

    class Factory : Decoder.Factory {

        private fun isApng(input: InputStream): Boolean {
            val data = ByteArray(1000)
            input.read(data, 0, 1000)
            return getMimeTypeFromPictureBinary(data, 1000) == MIME_IMAGE_APNG
        }

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val stream = result.source.source().peek().inputStream()
            return if (isApng(stream)) {
                AnimatedPngDecoder(result.source)
            } else {
                null
            }
        }
    }
}