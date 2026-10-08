package com.songaudit.fix

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Pictures through Android's own decoder and JPEG encoder. */
class AndroidImages : Images {

    override fun size(data: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        return o.outWidth to o.outHeight
    }

    override fun fit(data: ByteArray, max: Int): ByteArray? {
        val (w, h) = size(data)
        if (w <= 0 || h <= 0) return null
        // Decode at a power-of-two fraction first: a 3000 px scan never sits in memory whole.
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= max) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = max.toFloat() / maxOf(decoded.width, decoded.height)
        var bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).roundToInt(), (decoded.height * scale).roundToInt(), true)
        } else {
            decoded
        }
        if (bitmap.hasAlpha()) {
            // JPEG has no transparency: what was see-through goes white, not black.
            val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
            if (bitmap !== decoded) bitmap.recycle()
            bitmap = flat
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()
        return out.toByteArray()
    }
}
