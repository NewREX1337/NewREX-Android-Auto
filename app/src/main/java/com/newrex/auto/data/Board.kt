// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Karşılama ekranındaki bir öğe: saat ya da fotoğraf. Konum, ekranın oranı olarak (0..1, öğenin ortası). */
data class BoardItem(
    val id: String,
    val type: String,
    val file: String? = null,
    val cx: Float,
    val cy: Float,
    val size: Int = 1,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("type", type).put("file", file ?: "")
        .put("cx", cx.toDouble()).put("cy", cy.toDouble()).put("size", size)

    companion object {
        const val CLOCK = "clock"
        const val PHOTO = "photo"

        fun fromJson(o: JSONObject) = BoardItem(
            id = o.optString("id"),
            type = o.optString("type"),
            file = o.optString("file").ifBlank { null },
            cx = o.optDouble("cx", 0.5).toFloat(),
            cy = o.optDouble("cy", 0.5).toFloat(),
            size = o.optInt("size", 1).coerceIn(0, 2),
        )
    }
}

/** Telefondan seçilmiş, uygulamanın içine kopyalanmış fotoğraf. [c1]/[c2] fotoğrafın baskın renkleri. */
data class BoardPhoto(val file: String, val width: Int, val height: Int, val c1: Int, val c2: Int) {
    val aspect: Float get() = (width.toFloat() / height.coerceAtLeast(1)).coerceIn(0.5f, 2f)

    fun toJson(): JSONObject = JSONObject().put("file", file).put("w", width).put("h", height).put("c1", c1).put("c2", c2)

    companion object {
        fun fromJson(o: JSONObject) = BoardPhoto(o.optString("file"), o.optInt("w", 1), o.optInt("h", 1),
            o.optInt("c1", 0xFF7C5CFF.toInt()), o.optInt("c2", 0xFF2F80ED.toInt()))
    }
}

/**
 * Araçtaki karşılama ekranının içeriği. Fotoğraflar telefonda uygulamanın kendi klasörüne kopyalanır;
 * hiçbir yere gönderilmez, uygulama silinince silinir.
 */
object Board {
    private lateinit var sp: SharedPreferences
    private lateinit var dir: File

    val items = MutableStateFlow<List<BoardItem>>(emptyList())
    val photos = MutableStateFlow<List<BoardPhoto>>(emptyList())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        val app = context.applicationContext
        sp = app.getSharedPreferences("newrex_board", Context.MODE_PRIVATE)
        dir = File(app.filesDir, "board").apply { mkdirs() }
        photos.value = readList("photos") { BoardPhoto.fromJson(it) }.filter { File(dir, it.file).exists() }
        items.value = if (sp.contains("items")) readList("items") { BoardItem.fromJson(it) }
            .filter { it.type == BoardItem.CLOCK || photos.value.any { p -> p.file == it.file } }
        else listOf(BoardItem(newId(), BoardItem.CLOCK, cx = 0.3f, cy = 0.5f, size = 2))
    }

    fun file(name: String) = File(dir, name)
    fun photo(name: String?) = photos.value.firstOrNull { it.file == name }

    /** Ekranın renklerini belirleyen fotoğraf: ekrandaki en büyük fotoğraf. */
    fun themePhoto(): BoardPhoto? = items.value.filter { it.type == BoardItem.PHOTO }
        .maxByOrNull { it.size }?.let { photo(it.file) }

    fun hasClock() = items.value.any { it.type == BoardItem.CLOCK }

    fun update(item: BoardItem) {
        items.value = items.value.map { if (it.id == item.id) item else it }
        save()
    }

    fun remove(id: String) {
        items.value = items.value.filterNot { it.id == id }
        save()
    }

    fun addClock() {
        if (hasClock()) return
        items.value = items.value + BoardItem(newId(), BoardItem.CLOCK, cx = 0.3f, cy = 0.5f, size = 2)
        save()
    }

    fun addPhoto(file: String) {
        val (cx, cy) = freeSpot()
        items.value = items.value + BoardItem(newId(), BoardItem.PHOTO, file, cx, cy, size = 1)
        save()
    }

    /** Fotoğrafı tamamen siler (ekrandan da). */
    fun deletePhoto(file: String) {
        items.value = items.value.filterNot { it.file == file }
        photos.value = photos.value.filterNot { it.file == file }
        File(dir, file).delete()
        save()
    }

    /** Seçilen fotoğrafları küçültüp kopyalar ve ekrana ekler. Eklenen fotoğraf sayısını döndürür. */
    suspend fun import(context: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var added = 0
        for (uri in uris) {
            val bmp = runCatching { decode(context, uri) }.getOrNull() ?: continue
            val name = "${newId()}.jpg"
            val saved = runCatching { File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            if (saved.isFailure) continue
            val (c1, c2) = dominantColors(bmp)
            val photo = BoardPhoto(name, bmp.width, bmp.height, c1, c2)
            bmp.recycle()
            withContext(Dispatchers.Main) {
                photos.value = photos.value + photo
                addPhoto(name)
            }
            added++
        }
        added
    }

    private fun freeSpot(): Pair<Float, Float> {
        // Saat solda durduğu için fotoğraflar önce sağ yarıya yerleşir
        val spots = listOf(0.74f to 0.5f, 0.9f to 0.28f, 0.58f to 0.3f, 0.9f to 0.74f, 0.58f to 0.74f, 0.3f to 0.2f)
        val taken = items.value.map { it.cx to it.cy }
        return spots.firstOrNull { s -> taken.none { kotlin.math.abs(it.first - s.first) + kotlin.math.abs(it.second - s.second) < 0.15f } }
            ?: spots[items.value.size % spots.size]
    }

    private fun save() {
        val arr = JSONArray()
        items.value.forEach { arr.put(it.toJson()) }
        val p = JSONArray()
        photos.value.forEach { p.put(it.toJson()) }
        sp.edit().putString("items", arr.toString()).putString("photos", p.toString()).apply()
    }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val arr = runCatching { JSONArray(sp.getString(key, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun newId() = UUID.randomUUID().toString().take(8)

    // ---- Fotoğraf okuma ----

    private const val MAX_SIDE = 1600

    /** Fotoğrafı yönünü düzelterek ve en uzun kenarı [MAX_SIDE] olacak şekilde okur. */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        val cr = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                val s = info.size
                val f = maxOf(s.width, s.height) / MAX_SIDE.toFloat()
                if (f > 1f) d.setTargetSize((s.width / f).toInt(), (s.height / f).toInt())
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bmp = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (rotation == 0f) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
    }

    /** Fotoğrafın en baskın iki rengi (arka plan bu renklere uyum sağlar). */
    private fun dominantColors(bmp: Bitmap): Pair<Int, Int> {
        val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val bins = 12
        val weight = FloatArray(bins)
        val sums = Array(bins) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = small.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2] + 0.02f
            val b = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            weight[b] += w
            sums[b][0] += Color.red(c) * w; sums[b][1] += Color.green(c) * w; sums[b][2] += Color.blue(c) * w
        }
        if (small !== bmp) small.recycle()
        val order = weight.indices.sortedByDescending { weight[it] }
        fun avg(b: Int) = Color.rgb((sums[b][0] / weight[b]).toInt(), (sums[b][1] / weight[b]).toInt(), (sums[b][2] / weight[b]).toInt())
        val first = order[0]
        // İkinci renk: birinciden belirgin şekilde farklı tonda olan en güçlü renk
        val second = order.drop(1).firstOrNull { b -> val d = kotlin.math.abs(b - first); minOf(d, bins - d) >= 2 && weight[b] > 0.05f }
            ?: order[1]
        return vivid(avg(first)) to vivid(avg(second))
    }

    /** Arka plan çizgilerinde güzel görünsün diye rengi biraz canlandırır; gri fotoğraflarda yumuşak mavi. */
    private fun vivid(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.12f) return 0xFF8FA6D9.toInt()
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
        hsv[2] = hsv[2].coerceIn(0.75f, 1f)
        return Color.HSVToColor(hsv)
    }
}
