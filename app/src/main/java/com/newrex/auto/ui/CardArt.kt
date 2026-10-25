// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ana ekrandaki büyük kartların temalı arka plan çizimleri (TV, YouTube, Radyo, Web). Kodla çizilir,
 * resim dosyası yoktur; her ekran boyutunda keskin görünür. Çizim kartın sağ üstünde yoğunlaşır,
 * sol alttaki yazılar okunaklı kalsın diye oraya koyu bir geçiş eklenir.
 */
class CardArt(private val kind: Kind, private val color: Int, private val density: Float) : Drawable() {
    enum class Kind { TV, YOUTUBE, RADIO, WEB }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private fun dp(v: Float) = v * density
    private fun tint(alpha: Int, toWhite: Float = 0f): Int {
        val c = if (toWhite > 0f) blend(color, Color.WHITE, toWhite) else color
        return (c and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())

        // Sağ üstte rengin parıltısı
        fill.shader = RadialGradient(w * 0.85f, h * 0.15f, maxOf(w, h) * 0.75f,
            intArrayOf(tint(0x66), tint(0x14), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        when (kind) {
            Kind.TV -> drawTv(canvas, w, h)
            Kind.YOUTUBE -> drawYoutube(canvas, w, h)
            Kind.RADIO -> drawRadio(canvas, w, h)
            Kind.WEB -> drawWeb(canvas, w, h)
        }

        // Yazıların altına koyu geçiş
        fill.shader = LinearGradient(0f, h * 0.35f, 0f, h, 0, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        canvas.restore()
    }

    /** Antenden yayılan yayın dalgaları + eski tüplü ekran çizgileri. */
    private fun drawTv(c: Canvas, w: Float, h: Float) {
        stroke.color = tint(0x10, 0.5f)
        stroke.strokeWidth = dp(1f)
        var y = dp(3f)
        while (y < h) { c.drawLine(0f, y, w, y, stroke); y += dp(4f) }

        val cx = w * 0.82f
        val cy = h * 0.22f
        for (i in 1..6) {
            stroke.strokeWidth = dp(2.5f)
            stroke.color = tint(0xB0 - i * 22, 0.25f)
            val r = dp(18f) * i
            rect.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(rect, 200f, 140f, false, stroke)
            c.drawArc(rect, 20f, 140f, false, stroke)
        }
        fill.color = tint(0xFF, 0.6f)
        c.drawCircle(cx, cy, dp(6f), fill)
        // Anten direği
        stroke.strokeWidth = dp(3f)
        stroke.color = tint(0xC0, 0.4f)
        c.drawLine(cx, cy, cx - dp(14f), cy + dp(46f), stroke)
        c.drawLine(cx, cy, cx + dp(14f), cy + dp(46f), stroke)
    }

    /** Büyük, eğik oynat simgesi ve kenarda film şeridi delikleri. */
    private fun drawYoutube(c: Canvas, w: Float, h: Float) {
        val s = min(w, h) * 0.62f
        val cx = w * 0.74f
        val cy = h * 0.36f
        c.save()
        c.rotate(-12f, cx, cy)
        rect.set(cx - s * 0.7f, cy - s * 0.48f, cx + s * 0.7f, cy + s * 0.48f)
        fill.color = tint(0x55)
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, fill)
        stroke.strokeWidth = dp(2f)
        stroke.color = tint(0x99, 0.3f)
        c.drawRoundRect(rect, s * 0.22f, s * 0.22f, stroke)
        path.rewind()
        path.moveTo(cx - s * 0.18f, cy - s * 0.24f)
        path.lineTo(cx + s * 0.26f, cy)
        path.lineTo(cx - s * 0.18f, cy + s * 0.24f)
        path.close()
        fill.color = tint(0xDD, 0.85f)
        c.drawPath(path, fill)
        c.restore()

        // Film şeridi
        fill.color = tint(0x2A, 0.6f)
        c.drawRect(0f, h - dp(22f), w, h - dp(4f), fill)
        fill.color = 0x66000000
        var x = dp(6f)
        while (x < w) {
            rect.set(x, h - dp(18f), x + dp(9f), h - dp(8f))
            c.drawRoundRect(rect, dp(2f), dp(2f), fill)
            x += dp(16f)
        }
    }

    /** Ses dalgası ve ekolayzer çubukları. */
    private fun drawRadio(c: Canvas, w: Float, h: Float) {
        val base = h * 0.62f
        val bars = 18
        val gap = w / bars
        val heights = floatArrayOf(0.3f, 0.5f, 0.8f, 0.55f, 0.95f, 0.7f, 0.4f, 0.85f, 1f, 0.6f, 0.75f, 0.45f, 0.9f, 0.5f, 0.65f, 0.35f, 0.55f, 0.25f)
        for (i in 0 until bars) {
            val bh = h * 0.42f * heights[i]
            val x = gap * i + gap * 0.25f
            fill.shader = LinearGradient(0f, base - bh, 0f, base, tint(0xCC, 0.45f), tint(0x22), Shader.TileMode.CLAMP)
            rect.set(x, base - bh, x + gap * 0.5f, base)
            c.drawRoundRect(rect, gap * 0.25f, gap * 0.25f, fill)
        }
        fill.shader = null
        // İki iç içe sinüs dalgası
        for (k in 0..1) {
            path.rewind()
            val amp = h * (0.10f - k * 0.04f)
            val mid = h * (0.28f + k * 0.06f)
            for (i in 0..60) {
                val x = w * i / 60f
                val y = mid + amp * sin(i / 60f * Math.PI.toFloat() * 4f + k * 1.3f)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            stroke.strokeWidth = dp(2.5f - k)
            stroke.color = tint(0xB0 - k * 50, 0.5f)
            c.drawPath(path, stroke)
        }
    }

    /** Enlem-boylam çizgili küre ve çevresinde birbirine bağlı noktalar. */
    private fun drawWeb(c: Canvas, w: Float, h: Float) {
        val r = min(w, h) * 0.42f
        val cx = w * 0.76f
        val cy = h * 0.34f
        fill.color = tint(0x30)
        c.drawCircle(cx, cy, r, fill)
        stroke.strokeWidth = dp(1.6f)
        stroke.color = tint(0x99, 0.4f)
        c.drawCircle(cx, cy, r, stroke)
        for (i in 1..3) {
            val rx = r * cos(i * Math.PI.toFloat() / 8f * 1.6f).let { kotlin.math.abs(it) }
            rect.set(cx - rx, cy - r, cx + rx, cy + r)
            c.drawOval(rect, stroke)
        }
        for (i in -2..2) {
            if (i == 0) { c.drawLine(cx - r, cy, cx + r, cy, stroke); continue }
            val y = cy + r * i / 3f
            val half = kotlin.math.sqrt((r * r - (y - cy) * (y - cy)).coerceAtLeast(0f))
            c.drawLine(cx - half, y, cx + half, y, stroke)
        }
        // Ağ noktaları
        val pts = arrayOf(0.12f to 0.18f, 0.32f to 0.10f, 0.45f to 0.32f, 0.22f to 0.42f, 0.52f to 0.12f)
        stroke.strokeWidth = dp(1.2f)
        stroke.color = tint(0x70, 0.5f)
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            if ((i + j) % 2 == 0) c.drawLine(pts[i].first * w, pts[i].second * h, pts[j].first * w, pts[j].second * h, stroke)
        }
        fill.color = tint(0xE0, 0.6f)
        pts.forEach { (x, y) -> c.drawCircle(x * w, y * h, dp(3.5f), fill) }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        fun blend(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.rgb((Color.red(a) * u + Color.red(b) * t).toInt(), (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt())
        }
    }
}
