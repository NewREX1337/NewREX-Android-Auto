// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Kullanıcının telefondan yüklediği kendi araç görseli (PNG, JPG, WEBP...). Göstergenin ortasında hazır aracın
 * yerine görünür. Arka planı şeffaf PNG en güzel sonucu verir. Görsel küçültülüp (uzun kenar en fazla 1600 px)
 * uygulamanın kendi klasöründe saklanır; hiçbir yere gönderilmez.
 */
object CarImage {
    private const val MAX_SIDE = 1600
    val version = MutableStateFlow(0)
    @Volatile private var cached: Bitmap? = null

    private fun file(context: Context) = File(context.filesDir, "car_custom.png")

    fun has(context: Context) = file(context).exists()

    /** Bellekte tek kopya tutulur (ekran kartı belleğinde, çizerken işlemciyi yormaz). */
    fun bitmap(context: Context): Bitmap? {
        cached?.let { return it }
        val f = file(context)
        if (!f.exists()) return null
        return runCatching {
            val o = BitmapFactory.Options()
            if (Build.VERSION.SDK_INT >= 26) o.inPreferredConfig = Bitmap.Config.HARDWARE
            BitmapFactory.decodeFile(f.path, o)
        }.getOrNull()?.also { cached = it }
    }

    /** Seçilen görseli okur: dik çekilmişse düzeltir, uzun kenarı en fazla 1600 px olacak şekilde küçültür. */
    suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return@runCatching null
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: return@runCatching null
            val rotate = runCatching {
                cr.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }
            }.getOrNull() ?: 0f
            if (rotate != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotate) }, true)
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            bmp
        }.getOrNull()
    }

    /** Arka planı silinmiş görselin çevresindeki boş (şeffaf) kısmı kırpar; araç göstergede tam ortalanır. */
    fun trim(bmp: Bitmap): Bitmap? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) if ((px[row + x] ushr 24) > 24) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y
            }
        }
        if (r < 0 || (r - l) < w / 20 || (b - t) < h / 20) return null
        return Bitmap.createBitmap(bmp, l, t, r - l + 1, b - t + 1)
    }

    suspend fun save(context: Context, bmp: Bitmap): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.filesDir, "car_custom.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(file(context))) return@runCatching false
            cached = null
            version.value++
            true
        }.getOrDefault(false)
    }

    fun remove(context: Context) {
        file(context).delete()
        cached = null
        version.value++
    }
}
