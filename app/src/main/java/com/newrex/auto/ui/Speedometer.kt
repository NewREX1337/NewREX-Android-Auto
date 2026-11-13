// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
// Tasarım Araç Göstergesi uygulamasındaki (aynı geliştirici) hız göstergesinden uyarlandı.
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hız göstergesi: 0–180 km/s yay, vurgu renginde ilerleyen bant ve ibre ucu, ortada diskte büyük hız rakamı.
 * Hız sınırı seçiliyse diskin üstünde yazar, geçilince rakam ve bant kırmızı olur. İbre yumuşakça hareket eder.
 */
class Speedometer(context: Context) : View(context) {
    var limit = 0
        set(v) { field = v; invalidate() }
    /** Diskin üstündeki küçük yazı (ör. "GPS"). */
    var caption = ""
        set(v) { field = v; invalidate() }

    private var target = 0f
    private var known = false
    private var shown = 0f
    private var lastNs = 0L
    private val d = context.resources.displayMetrics.density

    fun setSpeed(kmh: Float?) {
        known = kmh != null
        target = (kmh ?: 0f).coerceIn(0f, MAX + 20)
        lastNs = 0L
        postInvalidateOnAnimation()
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
    private val arc = RectF()

    private companion object {
        const val MAX = 180f
        const val START = 140f
        const val SWEEP = 260f
        const val CARD = 0xE61B1E31.toInt()
        const val TEXT = 0xFFEEECFA.toInt()
        const val DIM = 0xFF9A97B5.toInt()
        const val TRACK = 0x26FFFFFF
        const val RED = 0xFFE11D48.toInt()
    }

    private fun Canvas.mid(s: String, x: Float, cy: Float, p: Paint) {
        val fm = p.fontMetrics
        drawText(s, x, cy - (fm.ascent + fm.descent) / 2, p)
    }

    override fun onDraw(c: Canvas) {
        val t = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((t - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = t
        shown += (target - shown) * (1 - exp(-dt * 6f))
        if (abs(target - shown) > 0.05f) postInvalidateOnAnimation() else { shown = target; lastNs = 0L }

        val accent = gaugeAccent()
        val over = limit > 0 && known && target > limit
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 6 * d

        // Dış kadran
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(0x8C0B0D14.toInt(), 0x660B0D14, Palette.withAlpha(accent, 0x22)), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        stroke.shader = null; stroke.color = Palette.withAlpha(accent, 0x40); stroke.strokeWidth = 1.2f * d
        c.drawCircle(cx, cy, r, stroke)

        // Bant
        val ra = r * 0.86f
        val bw = r * 0.075f
        arc.set(cx - ra, cy - ra, cx + ra, cy + ra)
        stroke.color = TRACK; stroke.strokeWidth = bw
        c.drawArc(arc, START, SWEEP, false, stroke)
        val frac = (shown / MAX).coerceIn(0f, 1f)
        val band = if (over) RED else accent
        if (frac > 0.002f) {
            val sweep = SWEEP * frac
            val g = SweepGradient(cx, cy, intArrayOf(Palette.withAlpha(band, 0x80), band, band), floatArrayOf(0f, (sweep / 360f).coerceAtLeast(0.01f), 1f))
            val m = android.graphics.Matrix(); m.setRotate(START, cx, cy); g.setLocalMatrix(m)
            stroke.color = Palette.withAlpha(band, 50); stroke.strokeWidth = bw * 2.2f
            c.drawArc(arc, START, sweep, false, stroke)
            stroke.shader = g; stroke.strokeWidth = bw
            c.drawArc(arc, START, sweep, false, stroke)
            stroke.shader = null
        }

        // Çentikler ve rakamlar
        val tickOut = ra - bw * 0.9f
        num.textSize = r * 0.085f
        var v = 0
        while (v <= MAX.toInt()) {
            val a = Math.toRadians((START + SWEEP * v / MAX).toDouble())
            val major = v % 20 == 0
            val len = if (major) r * 0.07f else r * 0.035f
            val inLimit = limit > 0 && v > limit
            stroke.color = when { v <= shown && shown > 0.5f -> band; inLimit -> Palette.withAlpha(RED, 0x99); else -> Palette.withAlpha(TEXT, if (major) 200 else 90) }
            stroke.strokeWidth = if (major) 2.6f * d else 1.6f * d
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * tickOut, cy + sa * tickOut, cx + ca * (tickOut - len), cy + sa * (tickOut - len), stroke)
            if (major) {
                val rl = tickOut - r * 0.15f
                num.color = if (v <= shown && v > 0) band else TEXT
                c.mid(v.toString(), cx + ca * rl, cy + sa * rl, num)
            }
            v += 10
        }

        // İbre ucu
        val na = Math.toRadians((START + SWEEP * frac).toDouble())
        val nc = cos(na).toFloat(); val ns = sin(na).toFloat()
        stroke.color = Palette.withAlpha(band, 70); stroke.strokeWidth = 9 * d
        c.drawLine(cx + nc * (ra - bw * 1.6f), cy + ns * (ra - bw * 1.6f), cx + nc * (ra + bw * 0.9f), cy + ns * (ra + bw * 0.9f), stroke)
        stroke.color = band; stroke.strokeWidth = 3.5f * d
        c.drawLine(cx + nc * (ra - bw * 1.6f), cy + ns * (ra - bw * 1.6f), cx + nc * (ra + bw * 0.9f), cy + ns * (ra + bw * 0.9f), stroke)

        // Orta disk
        val ri = r * 0.5f
        fill.color = 0xCC0B0D14.toInt()
        c.drawCircle(cx, cy, ri, fill)
        stroke.color = Palette.withAlpha(accent, 0x55); stroke.strokeWidth = 1.2f * d
        c.drawCircle(cx, cy, ri, stroke)

        big.textSize = r * 0.36f; big.color = if (over) RED else TEXT
        c.mid(shown.roundToInt().toString(), cx, cy - ri * 0.05f, big)
        num.textSize = r * 0.07f; num.color = DIM
        c.mid("km/s", cx, cy + ri * 0.42f, num)
        num.letterSpacing = 0.14f
        num.color = if (limit > 0) RED else band
        c.mid(if (limit > 0) "LIMIT $limit" else caption, cx, cy - ri * 0.6f, num)
        num.letterSpacing = 0f
    }
}
