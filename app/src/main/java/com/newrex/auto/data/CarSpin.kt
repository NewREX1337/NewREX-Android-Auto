// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
// Araç Göstergesi uygulamasındaki (aynı geliştirici) 360° araç görseli altyapısından uyarlandı.
package com.newrex.auto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.min

/**
 * Toyota C-HR (2025) paketleri ve renkleri. [mix]: Toyota'nın artık satmadığı renk; görsel aynı paketin beyaz
 * (089) ve siyah (209) fotoğraflarının kanal kanal karışımıyla üretilir.
 */
class CarPaint(val code: String, val name: String, val rgb: Int, val mix: FloatArray? = null)

data class CarGrade(val id: String, val name: String, val vehicle: Int, val paints: List<CarPaint>)

object CarCatalog {
    private const val TOKEN = "f4c9f3a9-b8c5-41fb-a631-04be59e18b57"
    const val FRAMES = 36
    const val W = 1000
    const val H = 450

    private val single = listOf(
        CarPaint("209", "Siyah", 0xFF0A0A0C.toInt()),
        CarPaint("089", "Galaksi Beyazı", 0xFFF2F6F8.toInt()),
        CarPaint("785", "Pasifik Mavi", 0xFF0E2C3A.toInt()),
        CarPaint("8N8", "Topaz Mavi", 0xFF3759AE.toInt()),
        CarPaint("1M2", "Antrasit", 0xFF4A4C51.toInt()),
        CarPaint("3U5", "Egzotik Kırmızı", 0xFF9C0A1C.toInt()),
        CarPaint("1L0", "Parlak Gümüş", 0xFFAEB0B3.toInt(), mix = floatArrayOf(0.69f, 0.705f, 0.72f)),
        CarPaint("1G3", "Metalik Gri", 0xFF5A5C61.toInt(), mix = floatArrayOf(0.205f, 0.215f, 0.235f)),
    )
    private val twoTone = listOf(
        CarPaint("2TB", "Egzotik Kırmızı / Siyah tavan", 0xFF9C0A1C.toInt()),
        CarPaint("M35", "Magma Kırmızı / Siyah tavan", 0xFFB2452E.toInt()),
        CarPaint("2MR", "Kristal Gümüş / Siyah tavan", 0xFF9A9897.toInt()),
        CarPaint("2VP", "Galaksi Beyazı / Siyah tavan", 0xFFF2F6F8.toInt()),
        CarPaint("2YB", "Pasifik Mavi / Siyah tavan", 0xFF0E2C3A.toInt()),
        CarPaint("M78", "Topaz Mavi / Siyah tavan", 0xFF3759AE.toInt()),
        CarPaint("M29", "Antrasit / Siyah tavan", 0xFF4A4C51.toInt()),
    )

    val grades = listOf(
        CarGrade("flame", "Flame", 103395, listOf(CarPaint("040", "Kar Beyazı", 0xFFFAFAFA.toInt())) + single),
        CarGrade("passion", "Passion", 103394, single),
        CarGrade("xsport", "Passion X-Sport", 103393, twoTone),
        CarGrade("gr", "GR Sport", 103392, twoTone),
    )

    fun grade(id: String) = grades.firstOrNull { it.id == id } ?: grades[1]
    fun paint(g: CarGrade, code: String) = g.paints.firstOrNull { it.code == code } ?: g.paints.first()

    fun frameUrl(g: CarGrade, p: CarPaint, i: Int) =
        "https://img-optimize.toyota-europe.com/ccis/zip/tr/product-token/$TOKEN/vehicle/${g.vehicle}" +
            "/width/$W/height/$H/scale-mode/1/image-quality/85/day-exterior-%02d_${p.code}.png".format(i)

    /** Tek fotoğraflı sürümde gösterilen açı: önden 3/4. */
    const val PHOTO_FRAME = 4

    val selectedGrade get() = if (com.newrex.auto.ui.CarVisual.FULL_SPIN) grade(Prefs.spinGrade) else grade("xsport")
    val selectedPaint get() = if (com.newrex.auto.ui.CarVisual.FULL_SPIN) paint(selectedGrade, Prefs.spinPaint) else paint(selectedGrade, "2VP")
}

/**
 * Seçili aracın 36 açıdan (10°'de bir) görüntüsü. İlk açılışta Toyota'nın sunucusundan indirilir, sonra telefonda
 * saklanır (uygulamaya gömülmez). Kareler ekran kartı belleğine açılır ki çevirirken takılma olmasın.
 */
object CarFrames {
    class Spin(val key: String) {
        val frames = arrayOfNulls<Bitmap>(CarCatalog.FRAMES)
        val ready get() = frames.count { it != null }
    }

    val current = MutableStateFlow<Spin?>(null)
    val version = MutableStateFlow(0)
    val error = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun key(g: CarGrade, p: CarPaint) = "${g.vehicle}_${p.code}_s1"

    fun load(context: Context, startFrame: Int = 20) {
        val g = CarCatalog.selectedGrade
        val p = CarCatalog.selectedPaint
        val k = key(g, p)
        val cur = current.value
        val need = if (com.newrex.auto.ui.CarVisual.FULL_SPIN) CarCatalog.FRAMES else 1
        if (cur?.key == k && (cur.ready >= need || job?.isActive == true)) return
        job?.cancel()
        val spin = Spin(k)
        current.value = spin
        error.value = null
        version.value++
        val dir = File(context.filesDir, "cars/$k").apply { mkdirs() }
        // Tek fotoğraflı sürümde sadece öndeki açı indirilir (36 kare yerine 1: daha az internet ve bellek)
        val order = if (com.newrex.auto.ui.CarVisual.FULL_SPIN) (0 until CarCatalog.FRAMES).sortedBy { ringDist(it, startFrame) }
            else listOf(CarCatalog.PHOTO_FRAME)
        job = scope.launch {
            val gate = Semaphore(4)
            val jobs = order.map { i ->
                launch {
                    gate.withPermit {
                        val f = File(dir, "%02d.png".format(i))
                        val ok = f.exists() || if (p.mix != null) mixFrame(context, g, p, i, f) else download(CarCatalog.frameUrl(g, p, i), f)
                        if (!ok) return@withPermit
                        val bmp = decode(f)
                        if (bmp == null) { f.delete(); return@withPermit }
                        if (current.value === spin) { spin.frames[i] = bmp; version.value++ }
                    }
                }
            }
            jobs.forEach { it.join() }
            if (isActive && spin.ready < order.size && current.value === spin) {
                error.value = if (spin.ready == 0) com.newrex.auto.t("Araç görseli indirilemedi. İnternet bağlantısını kontrol et.")
                else null
            }
        }
    }

    private fun ringDist(a: Int, b: Int): Int {
        val d = abs(a - b) % CarCatalog.FRAMES
        return min(d, CarCatalog.FRAMES - d)
    }

    private suspend fun download(url: String, target: File): Boolean {
        repeat(4) { attempt ->
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val bytes = r.body?.bytes()
                    // Sunucu yoğunken PNG yerine JSON hata dönebiliyor; sadece gerçek PNG kaydedilir
                    if (r.isSuccessful && bytes != null && bytes.size > 8 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()) {
                        val tmp = File(target.path + ".tmp")
                        tmp.writeBytes(bytes)
                        return tmp.renameTo(target)
                    }
                }
            } catch (e: Exception) {
                Log.w("NewREX", "Araç görseli indirilemedi: ${e.message}")
            }
            delay(600L * (attempt + 1))
        }
        return false
    }

    /** Satıştan kalkmış renk: beyaz ve siyah fotoğraf karıştırılarak o renk üretilir. */
    private suspend fun mixFrame(context: Context, g: CarGrade, p: CarPaint, i: Int, target: File): Boolean {
        val mix = p.mix ?: return false
        val src = listOf("089", "209").map { code ->
            val paint = CarCatalog.paint(g, code)
            val f = File(context.filesDir, "cars/${key(g, paint)}/%02d.png".format(i))
            f.parentFile?.mkdirs()
            if (!f.exists() && !download(CarCatalog.frameUrl(g, paint, i), f)) return false
            f
        }
        return runCatching {
            val o = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val w = BitmapFactory.decodeFile(src[0].path, o) ?: return false
            val b = BitmapFactory.decodeFile(src[1].path, o) ?: return false
            if (w.width != b.width || w.height != b.height) return false
            val n = w.width * w.height
            val pw = IntArray(n).also { w.getPixels(it, 0, w.width, 0, 0, w.width, w.height) }
            val pb = IntArray(n).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val out = IntArray(n)
            for (k in 0 until n) {
                val cw = pw[k]; val cb = pb[k]
                fun ch(shift: Int, m: Float): Int {
                    val vb = (cb shr shift) and 0xFF
                    val vw = (cw shr shift) and 0xFF
                    return (vb + (vw - vb) * m).toInt().coerceIn(0, 255)
                }
                val a = maxOf(cw ushr 24, cb ushr 24)
                out[k] = (a shl 24) or (ch(16, mix[0]) shl 16) or (ch(8, mix[1]) shl 8) or ch(0, mix[2])
            }
            val bmp = Bitmap.createBitmap(out, w.width, w.height, Bitmap.Config.ARGB_8888)
            val tmp = File(target.path + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            w.recycle(); b.recycle(); bmp.recycle()
            tmp.renameTo(target)
        }.getOrDefault(false)
    }

    private fun decode(f: File): Bitmap? = runCatching {
        val o = BitmapFactory.Options()
        if (Build.VERSION.SDK_INT >= 26) o.inPreferredConfig = Bitmap.Config.HARDWARE
        // 36 kare bellekte durur: %80 boyutta ~65 MB yerine ~41 MB (araç ekranında fark edilmez)
        o.inScaled = true; o.inDensity = 10; o.inTargetDensity = 8
        BitmapFactory.decodeFile(f.path, o)
    }.getOrNull()
}
