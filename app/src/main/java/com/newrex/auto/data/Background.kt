// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Araç ekranının özel arka plan fotoğrafı. Telefondan seçilen fotoğraf küçültülüp (uzun kenar en fazla 2560 px)
 * uygulamanın kendi klasörüne kaydedilir; ekranda ortalanıp ekranı dolduracak şekilde kırpılarak gösterilir.
 * Fotoğraf yalnızca telefonda kalır, hiçbir yere gönderilmez.
 */
object Background {
    private const val MAX_SIDE = 2560

    fun file(context: Context) = File(context.filesDir, "background.jpg")

    fun has(context: Context) = Prefs.bgImageVersion > 0 && file(context).exists()

    /** Ekranda gösterilecek boyutta okur (bellek şişmesin). */
    fun load(context: Context, maxW: Int, maxH: Int): Bitmap? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    suspend fun import(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cr = context.contentResolver
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            var bmp = cr.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching false
            // Telefonla dik çekilen fotoğraf yan dönmesin
            val rotate = cr.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            if (rotate != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotate) }, true)
            val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val tmp = File(context.filesDir, "background.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            tmp.renameTo(file(context))
            Prefs.bgImageVersion = System.currentTimeMillis()
            true
        }.getOrDefault(false)
    }

    fun remove(context: Context) {
        file(context).delete()
        Prefs.bgImageVersion = 0
    }
}
