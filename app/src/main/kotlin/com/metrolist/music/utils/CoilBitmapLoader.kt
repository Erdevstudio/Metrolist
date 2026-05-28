/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.content.Context
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

class CoilBitmapLoader(
    private val context: Context,
    private val scope: CoroutineScope,
) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    private fun createFallbackBitmap(): Bitmap = createBitmap(64, 64)

    /**
     * Creates a fully independent software copy of this bitmap.
     *
     * FIX (Android 15 crash): Replaces the previous compress+decode approach with Bitmap.copy().
     * The old compress(PNG)+BitmapFactory.decodeByteArray() had a TOCTOU race condition:
     *   1. isRecycled check → false (OK)
     *   2. Coil evicts & recycles the bitmap under Android 15 memory pressure (between steps)
     *   3. compress() throws on the recycled bitmap → catch returns createFallbackBitmap()
     *
     * Bitmap.copy() is a single atomic JNI call that copies pixel data directly, giving
     * virtually no window for a concurrent recycle to cause "cannot use a recycled source
     * in createBitmap" in android.media.MediaMetadata$Builder.scaleBitmap().
     *
     * The HARDWARE config guard ensures we never pass a hardware-backed bitmap to
     * MediaSession (hardware bitmaps can't be scaled by the legacy MediaMetadata builder).
     */
    private fun Bitmap.createSafeCopy(): Bitmap {
        if (isRecycled) return createFallbackBitmap()
        return try {
            val safeConfig = config?.takeIf { it != Bitmap.Config.HARDWARE }
                ?: Bitmap.Config.ARGB_8888
            copy(safeConfig, false) ?: createFallbackBitmap()
        } catch (e: Exception) {
            Timber.tag("CoilBitmapLoader").w(e, "Failed to copy bitmap, using fallback")
            createFallbackBitmap()
        }
    }

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        scope.future(Dispatchers.IO) {
            try {
                // BitmapFactory.decodeByteArray() already produces an independent bitmap
                // not owned by Coil, so no need to copy it again.
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
                        .allowHardware(false) // Ensure software bitmap for MediaSession compat
                        .build()

                when (val result = context.imageLoader.execute(request)) {
                    is ErrorResult -> {
                        Timber.tag("CoilBitmapLoader").w(
                            result.throwable, "Coil image load failed, using fallback"
                        )
                        createFallbackBitmap()
                    }

                    is SuccessResult -> {
                        try {
                            // toBitmap() returns the Coil-managed bitmap directly.
                            // createSafeCopy() makes a new bitmap we own exclusively,
                            // preventing the "recycled source" crash in MediaSession.
                            result.image.toBitmap().createSafeCopy()
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
        val artworkUri =
            metadata.artworkUri
                ?: metadata.extras?.getString("artwork_uri")?.toUri()
                ?: return null
        return loadBitmap(artworkUri)
    }
}
