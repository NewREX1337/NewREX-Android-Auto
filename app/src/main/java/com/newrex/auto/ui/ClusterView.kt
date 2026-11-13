// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/** Göstergenin vurgu rengi: uygulamanın tema rengi (gri temada biraz açılır ki parlasın). */
fun gaugeAccent(): Int {
    val hsv = FloatArray(3); Color.colorToHSV(Palette.accent, hsv)
    if (hsv[1] < 0.2f) hsv[2] = maxOf(hsv[2], 0.85f)
    return Color.HSVToColor(hsv)
}

/**
 * Dijital gösterge paneli (premium otomobil göstergelerindeki gibi): solda köşeli çerçeveli hız kadranı, sağda
 * P/D ve canlı sürüş puanı kadranı, altta ince bilgi şeridi (menzil, saat, konum, dış sıcaklık). Ortası boştur;
 * oraya araç ya da harita konur ([centerRect]). Dokunmaları almaz, sadece çizer.
 */
class ClusterView(context: Context) : View(context) {
    var speed: Float? = null
        set(v) { field = v; target = (v ?: 0f).coerceIn(0f, MAX); lastNs = 0L; postInvalidateOnAnimation() }
    var score = 100
        set(v) { if (v != field) { field = v; invalidate() } }
    var driving = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var speedCaption = "km/s"
        set(v) { if (v != field) { field = v; invalidate() } }
    /** Alt şerit: (sol, saat, orta, sağ). */
    var strip = arrayOf("", "", "", "")
        set(v) { if (!v.contentEquals(field)) { field = v; invalidate() } }

    private var staticBmp: android.graphics.Bitmap? = null
    private var staticKey = 0
    private val arcRect = RectF()

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        staticBmp?.recycle(); staticBmp = null
    }

    private var target = 0f
    private var shown = 0f
    private var lastNs = 0L
    private val d = context.resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private companion object {
        const val MAX = 260f
        const val TEXT = 0xFFF2F2F5.toInt()
        const val DIM = 0x99FFFFFF.toInt()
        const val RED = 0xFFE11D48.toInt()
    }

    /** Ortadaki boş alan (araç/harita için), görünümün kendi koordinatlarında. */
    fun centerRect(w: Int, h: Int): RectF = RectF(w * 0.27f, 0f, w * 0.73f, h * 0.9f)

    override fun onDraw(c: Canvas) {
        val t = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((t - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = t
        shown += (target - shown) * (1 - exp(-dt * 6f))
        if (abs(target - shown) > 0.05f) postInvalidateOnAnimation() else { shown = target; lastNs = 0L }

        val w = width.toFloat(); val h = height.toFloat()
        val accent = gaugeAccent()
        val stripY = h * 0.92f
        // Dar (8 inç gibi) ekranlarda kadran yanlardan taşmasın
        val r = minOf(h * 0.44f, w * 0.205f)
        val cy = h * 0.47f
        // Çerçeve, parıltı ve degrade gibi değişmeyen kısımlar bir kez çizilip saklanır; her karede sadece ibre ve yazılar
        val key = (width * 31 + height) * 31 + accent
        if (staticBmp == null || staticKey != key) {
            staticBmp?.recycle()
            staticBmp = android.graphics.Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also { b ->
                val sc = Canvas(b)
                frame(sc, w * 0.235f, cy, r, 110f, 140f, w * 0.27f, accent, left = true)
                frame(sc, w * 0.765f, cy, r, 70f, -140f, w * 0.73f, accent, left = false)
                line.strokeWidth = 1f * d
                line.shader = LinearGradient(w * 0.08f, 0f, w * 0.92f, 0f, intArrayOf(0, Palette.withAlpha(accent, 0x70), Palette.withAlpha(accent, 0x70), 0), floatArrayOf(0f, 0.2f, 0.8f, 1f), Shader.TileMode.CLAMP)
                sc.drawLine(w * 0.08f, stripY - 16 * d, w * 0.92f, stripY - 16 * d, line)
                line.shader = null
            }
            staticKey = key
        }
        c.drawBitmap(staticBmp!!, 0f, 0f, null)
        leftGauge(c, w, h, r, cy, accent)
        rightGauge(c, w, h, r, cy, accent)
        bottomStrip(c, w, stripY, accent)
    }

    /** Sol: sol kenara yaslı yarım çember kadran (0 altta, 260 üstte), üst ve alt uçlardan ortaya uzanan çizgiler. */
    private fun leftGauge(c: Canvas, w: Float, h: Float, r: Float, cy: Float, accent: Int) {
        val cx = w * 0.235f
        val start = 110f; val sweep = 140f   // 110° (sol alt) → 250° (sol üst), saat yönünde sola doğru
        // Çentikler ve rakamlar (iç kenarda)
        txt.typeface = regular; txt.textAlign = Paint.Align.CENTER
        txt.textSize = r * 0.085f
        var v = 0
        while (v <= MAX) {
            val a = Math.toRadians((start + sweep * v / MAX).toDouble())
            val major = v % 20 == 0
            val r1 = r * 0.96f
            val r2 = r1 - if (major) r * 0.07f else r * 0.035f
            line.strokeWidth = if (major) 2f * d else 1.2f * d
            line.color = if (v <= shown && shown > 0.5f) accent else 0x66FFFFFF
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), line)
            if (v % 40 == 0) {
                val rt = r2 - r * 0.1f
                txt.color = if (v <= shown && v > 0) accent else TEXT
                c.drawText("$v", cx + rt * cos(a).toFloat(), cy + rt * sin(a).toFloat() + txt.textSize * 0.35f, txt)
            }
            v += 10
        }
        // İbre: kadranın kenarından içe doğru parlayan ince çizgi
        val a = Math.toRadians((start + sweep * (shown / MAX).coerceIn(0f, 1f)).toDouble())
        val o = r * 1.0f; val i = r * 0.55f
        // Parıltı: bulanıklık filtresi yerine iki yarı saydam kalın çizgi (her karede bulanıklık hesaplamak pahalı)
        glow.maskFilter = null; glow.shader = null
        glow.color = Palette.withAlpha(accent, 0x2A); glow.strokeWidth = 11 * d
        c.drawLine(cx + o * cos(a).toFloat(), cy + o * sin(a).toFloat(), cx + i * cos(a).toFloat(), cy + i * sin(a).toFloat(), glow)
        glow.color = Palette.withAlpha(accent, 0x60); glow.strokeWidth = 6 * d
        c.drawLine(cx + o * cos(a).toFloat(), cy + o * sin(a).toFloat(), cx + i * cos(a).toFloat(), cy + i * sin(a).toFloat(), glow)
        line.color = Color.WHITE; line.strokeWidth = 2.6f * d
        c.drawLine(cx + o * cos(a).toFloat(), cy + o * sin(a).toFloat(), cx + i * cos(a).toFloat(), cy + i * sin(a).toFloat(), line)
        // Dijital hız
        txt.textAlign = Paint.Align.LEFT
        txt.typeface = light; txt.textSize = r * 0.42f; txt.color = TEXT
        val sx = cx - r * 0.38f
        c.drawText(shown.roundToInt().toString(), sx, cy + txt.textSize * 0.32f, txt)
        val numW = txt.measureText(shown.roundToInt().toString())
        txt.typeface = regular; txt.textSize = r * 0.08f; txt.color = DIM
        c.drawText(speedCaption, sx + numW + 6 * d, cy + txt.textSize * 0.4f, txt)
        line.color = Palette.withAlpha(accent, 0x80); line.strokeWidth = 1.2f * d
        c.drawLine(sx, cy + r * 0.26f, sx + r * 0.62f, cy + r * 0.26f, line)
    }

    /** Sağ: aynalı kadran; ortada P/D, yayda canlı sürüş puanı (0–100). */
    private fun rightGauge(c: Canvas, w: Float, h: Float, r: Float, cy: Float, accent: Int) {
        val cx = w * 0.765f
        val start = 70f; val sweep = -140f   // 70° (sağ alt) → -70° (sağ üst)
        txt.typeface = regular; txt.textAlign = Paint.Align.CENTER; txt.textSize = r * 0.085f
        val frac = (score / 100f).coerceIn(0f, 1f)
        for (i in 0..10) {
            val a = Math.toRadians((start + sweep * i / 10f).toDouble())
            val r1 = r * 0.96f; val r2 = r1 - if (i % 2 == 0) r * 0.07f else r * 0.035f
            line.strokeWidth = if (i % 2 == 0) 2f * d else 1.2f * d
            line.color = if (i / 10f <= frac) scoreColor(i * 10) else 0x66FFFFFF
            c.drawLine(cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), cx + r2 * cos(a).toFloat(), cy + r2 * sin(a).toFloat(), line)
            if (i % 2 == 0) {
                val rt = r2 - r * 0.1f
                txt.color = TEXT
                c.drawText("${i * 10}", cx + rt * cos(a).toFloat(), cy + rt * sin(a).toFloat() + txt.textSize * 0.35f, txt)
            }
        }
        // Puan yayı (iç tarafta ince bant)
        val ra = r * 1.0f
        arcRect.set(cx - ra, cy - ra, cx + ra, cy + ra)
        glow.maskFilter = null; glow.strokeWidth = 4 * d; glow.color = Palette.withAlpha(scoreColor(score), 0xCC)
        c.drawArc(arcRect, start, sweep * frac, false, glow)
        // Büyük P / D ve alt yazılar
        txt.textAlign = Paint.Align.RIGHT
        txt.typeface = light; txt.textSize = r * 0.42f; txt.color = TEXT
        val ex = cx + r * 0.38f
        c.drawText(if (driving) "D" else "P", ex, cy + txt.textSize * 0.32f, txt)
        txt.typeface = medium; txt.textSize = r * 0.075f; txt.color = accent; txt.letterSpacing = 0.15f
        c.drawText(if (driving) com.newrex.auto.t("SÜRÜŞ") else "PARK", ex, cy - r * 0.32f, txt)
        txt.letterSpacing = 0f
        txt.typeface = regular; txt.textSize = r * 0.08f; txt.color = DIM
        c.drawText(com.newrex.auto.t("Puan {0}", score), ex, cy + r * 0.38f, txt)
        line.color = Palette.withAlpha(accent, 0x80); line.strokeWidth = 1.2f * d
        c.drawLine(ex - r * 0.62f, cy + r * 0.26f, ex, cy + r * 0.26f, line)
    }

    private fun scoreColor(v: Int) = when { v >= 85 -> 0xFF22C55E.toInt(); v >= 60 -> 0xFFF59E0B.toInt(); else -> RED }

    /**
     * Kadran çerçevesi: yay + iki uçtan ortaya doğru uzanan, sona doğru solan yatay çizgiler (köşeli görünüm).
     * [innerX]: çizgilerin bittiği x.
     */
    private fun frame(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float, innerX: Float, accent: Int, left: Boolean) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        val a0 = Math.toRadians(start.toDouble()); val a1 = Math.toRadians((start + sweep).toDouble())
        val p0x = cx + r * cos(a0).toFloat(); val p0y = cy + r * sin(a0).toFloat()
        val p1x = cx + r * cos(a1).toFloat(); val p1y = cy + r * sin(a1).toFloat()
        path.rewind()
        path.moveTo(innerX, p0y); path.lineTo(p0x, p0y)
        path.arcTo(rect, start, sweep)
        path.lineTo(innerX, p1y)
        // Zemin: çok hafif koyu dolgu
        fill.shader = LinearGradient(if (left) cx - r else cx + r, 0f, innerX, 0f, 0x660B0D14, 0x220B0D14, Shader.TileMode.CLAMP)
        val area = Path(path); area.close()
        c.drawPath(area, fill)
        fill.shader = null
        glow.maskFilter = BlurMaskFilter(6 * d, BlurMaskFilter.Blur.NORMAL)
        glow.strokeWidth = 6 * d
        glow.shader = LinearGradient(if (left) cx - r else cx + r, 0f, innerX, 0f, Palette.withAlpha(accent, 0x80), 0, Shader.TileMode.CLAMP)
        c.drawPath(path, glow)
        glow.shader = null; glow.maskFilter = null
        line.strokeWidth = 2f * d
        line.shader = LinearGradient(if (left) cx - r else cx + r, 0f, innerX, 0f, accent, Palette.withAlpha(accent, 0x10), Shader.TileMode.CLAMP)
        c.drawPath(path, line)
        line.shader = null
        // Uç noktalarda küçük parlak noktalar
        fill.color = accent
        c.drawCircle(p0x, p0y, 2.5f * d, fill); c.drawCircle(p1x, p1y, 2.5f * d, fill)
    }

    /** Alt şerit: ince ayırıcı çizgi, üstünde dört bilgi. */
    private fun bottomStrip(c: Canvas, w: Float, y: Float, accent: Int) {
        txt.typeface = medium; txt.textSize = 15 * d; txt.color = TEXT; txt.letterSpacing = 0.02f
        txt.textAlign = Paint.Align.LEFT; c.drawText(strip[0], w * 0.1f, y, txt)
        txt.textAlign = Paint.Align.CENTER; c.drawText(strip[1], w * 0.3f, y, txt)
        txt.typeface = regular; c.drawText(strip[2], w * 0.5f, y, txt)
        txt.typeface = medium; txt.textAlign = Paint.Align.RIGHT; c.drawText(strip[3], w * 0.9f, y, txt)
        txt.letterSpacing = 0f
    }
}
