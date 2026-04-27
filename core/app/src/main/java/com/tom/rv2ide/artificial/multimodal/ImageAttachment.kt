/*
 * Holds the user's pending image attachment for the next AI request. The
 * Chat tab populates this on image-picker pick; OpenAI-compat / OpenRouter
 * agents read it and emit a multimodal user message
 *   { role: "user", content: [ { type:"text", text:"..." }, { type:"image_url", image_url:{url:"data:..."} } ] }
 * which every modern vision-capable model on those providers understands.
 *
 * The attachment is cleared automatically after the next request is sent so
 * one image doesn't leak into the next unrelated prompt.
 */
package com.tom.rv2ide.artificial.multimodal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageAttachment {

    /** Maximum bytes of compressed image we'll send. Keeps prompts under ~6MB. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /** Pending image as a `data:image/...;base64,...` URL. Null if none. */
    @Volatile
    var pendingDataUrl: String? = null
        private set

    /** Display label for the chip ("photo.jpg • 320 KB"). */
    @Volatile
    var pendingLabel: String? = null
        private set

    fun set(dataUrl: String, label: String) {
        pendingDataUrl = dataUrl
        pendingLabel = label
    }

    fun clear() {
        pendingDataUrl = null
        pendingLabel = null
    }

    fun hasPending(): Boolean = pendingDataUrl != null

    /**
     * Read the user-picked image from the given URI, downscale to at most
     * [maxEdge] pixels on the longest edge (so vision models don't reject huge
     * photos), JPEG-compress at quality 85, and produce a `data:image/jpeg;base64,...`
     * URL. Returns null on any failure.
     */
    fun encodeFromUri(
        resolver: ContentResolver,
        uri: Uri,
        maxEdge: Int = 1600,
    ): String? {
        return try {
            val bytes = resolver.openInputStream(uri).use { it?.readBytes() } ?: return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            val sample = computeInSampleSize(opts.outWidth, opts.outHeight, maxEdge)
            val decode = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: return null
            val scaled = scaleToMaxEdge(bitmap, maxEdge)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            var jpeg = out.toByteArray()
            // Last-resort guardrail: if it's still > MAX_BYTES, recompress harder.
            var quality = 75
            while (jpeg.size > MAX_BYTES && quality >= 35) {
                out.reset()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                jpeg = out.toByteArray()
                quality -= 10
            }
            "data:image/jpeg;base64,${Base64.encodeToString(jpeg, Base64.NO_WRAP)}"
        } catch (_: Throwable) {
            null
        }
    }

    private fun computeInSampleSize(srcW: Int, srcH: Int, maxEdge: Int): Int {
        if (srcW <= 0 || srcH <= 0) return 1
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width; val h = src.height
        if (w <= maxEdge && h <= maxEdge) return src
        val scale = maxEdge.toFloat() / maxOf(w, h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
