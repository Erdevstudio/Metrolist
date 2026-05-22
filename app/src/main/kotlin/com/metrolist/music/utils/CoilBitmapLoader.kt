/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import coil3.imageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import timber.log.Timber
import java.io.ByteArrayOutputStream

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap = createBitmap(64, 64)

    /**
     * Returns a fully independent, software-rendered copy of this bitmap that is safe to hand
     * off to the Android legacy MediaSession (which internally calls Bitmap.createScaledBitmap).
     *
     * Why Bitmap.copy() instead of PNG compress/decode:
     *  - copy() is an atomic native operation — it either succeeds fully or throws.
     *  - On Xiaomi HyperOS (Android 15), compress() can return false without throwing when
     *    the source bitmap is recycled by the system's aggressive memory manager between the
     *    isRecycled check and the actual compression, leaving a partial/corrupt byte stream.
     *    BitmapFactory.decodeByteArray() on that corrupt stream may produce a "zombie" Bitmap
     *    whose Java isRecycled() == false but whose native pixel data is already freed, causing
     *    the "cannot use a recycled source in createBitmap" crash inside MediaMetadata$Builder.
     *  - Hardware bitmaps (Bitmap.Config.HARDWARE) cannot be compressed; copy() handles them
     *    correctly by specifying an explicit software config.
     */
    private fun Bitmap.createIndependentCopy(): Bitmap {
        if (isRecycled) return createFallbackBitmap()
        return try {
            // Use a software config so the copy is always drawable by Android's legacy session.
            val softwareConfig = config
                ?.takeIf { it != Bitmap.Config.HARDWARE }
                ?: Bitmap.Config.ARGB_8888
            copy(softwareConfig, false)
                ?.takeIf { !it.isRecycled }
                ?: createFallbackBitmap()
        } catch (e: Exception) {
            // Secondary fallback: PNG encode → decode (slower but avoids any copy() edge cases).
            try {
                val stream = ByteArrayOutputStream()
                val compressed = compress(Bitmap.CompressFormat.PNG, 100, stream)
                if (!compressed || stream.size() == 0) return createFallbackBitmap()
                BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
                    ?: createFallbackBitmap()
            } catch (e2: Exception) {
                Timber.tag("CoilBitmapLoader").w(e2, "Both copy() and compress() failed")
                createFallbackBitmap()
            }
        }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                // BitmapFactory.decodeByteArray already allocates a fresh independent bitmap;
                // no additional copy needed here.
                BitmapFactory.decodeByteArray(data, 0, data.size) ?: createFallbackBitmap()
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to decode bitmap data")
                createFallbackBitmap()
            }
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                val request =
                    ImageRequest
                        .Builder(context)
                        .data(uri)
                        .allowHardware(false)
                        .build()

                when (val result = context.imageLoader.execute(request)) {
                    is ErrorResult -> {
                        createFallbackBitmap()
                    }

                    is SuccessResult -> {
                        try {
                            // Keep a local reference to result.image so the BitmapImage
                            // is not eligible for GC before createIndependentCopy() completes.
                            val image = result.image
                            val bitmap = image.toBitmap()
                            bitmap.createIndependentCopy()
                        } catch (e: Exception) {
                            Timber.tag("CoilBitmapLoader").w(e, "Failed to convert image to bitmap")
                            createFallbackBitmap()
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("CoilBitmapLoader").w(e, "Failed to load bitmap from uri")
                createFallbackBitmap()
            }
        }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap>? {
        metadata.artworkData?.let { return decodeBitmap(it) }
        val artworkUri = metadata.artworkUri ?: metadata.extras?.getString("artwork_uri")?.toUri() ?: return null
        return loadBitmap(artworkUri)
    }
}
