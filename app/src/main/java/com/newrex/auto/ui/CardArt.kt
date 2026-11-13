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
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ana ekrandaki kartların temalı arka plan çizimleri (TV, YouTube, Radyo, Web, Hava). Kodla çizilir,
 * resim dosyası yoktur; her ekran boyutunda keskin görünür. Çizim kartın sağında yoğunlaşır,
 * soldaki yazılar okunaklı kalsın diye oraya koyu bir geçiş eklenir.
 */
class CardArt(private val kind: Kind, private val color: Int, private val density: Float) : Drawable() {
    enum class Kind { TV, YOUTUBE, RADIO, WEB, WEATHER }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private fun dp(v: Float) = v * density
    private fun tint(alpha: Int, toWhite: Float = 0f): Int {
        val c = if (toWhite > 0f) blend(color, Color.WHITE, toWhite) else color
        return (c and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    }

    // Çizim bir kez resme dönüştürülür; her karede onlarca eğri yeniden çizilmez (araç ekranında kasmayı azaltır)
    private var cache: android.graphics.Bitmap? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        var bmp = cache
        if (bmp == null || bmp.width != b.width() || bmp.height != b.height()) {
            bmp = android.graphics.Bitmap.createBitmap(b.width(), b.height(), android.graphics.Bitmap.Config.ARGB_8888)
            render(Canvas(bmp), b.width().toFloat(), b.height().toFloat())
            cache = bmp
        }
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    private fun render(canvas: Canvas, w: Float, h: Float) {
        canvas.save()
        canvas.clipRect(0f, 0f, w, h)

        // Sağ üstte rengin parıltısı
        fill.shader = RadialGradient(w * 0.95f, h * 0.05f, maxOf(w, h) * 0.95f,
            intArrayOf(tint(0xB0), tint(0x38), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        when (kind) {
            Kind.TV -> drawTv(canvas, w, h)
            Kind.YOUTUBE -> drawYoutube(canvas, w, h)
            Kind.RADIO -> drawRadio(canvas, w, h)
            Kind.WEB -> drawWeb(canvas, w, h)
            Kind.WEATHER -> drawWeather(canvas, w, h)
        }

        // Yazıların altına soldan koyu geçiş
        fill.shader = LinearGradient(0f, 0f, w * 0.62f, 0f, 0xA6000000.toInt(), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        canvas.restore()
    }

    /** Üst üste sıralanan, sağa doğru yükselen tepeler. */
    private fun drawTv(c: Canvas, w: Float, h: Float) {
        for (k in 0..3) {
            val rise = h * (0.30f + k * 0.10f)
            val floor = h * (0.98f - k * 0.04f)
            fun y(t: Float) = floor - rise * t * t - h * 0.035f * sin(t * 9f + k * 1.7f)
            path.rewind()
            path.moveTo(w * 0.35f, h)
            for (i in 0..50) {
                val t = i / 50f
                path.lineTo(w * (0.35f + 0.65f * t), y(t))
            }
            path.lineTo(w, h)
            path.close()
            fill.color = tint(0x34 - k * 6)
            c.drawPath(path, fill)
            path.rewind()
            for (i in 0..50) {
                val t = i / 50f
                val x = w * (0.35f + 0.65f * t)
                if (i == 0) path.moveTo(x, y(t)) else path.lineTo(x, y(t))
            }
            stroke.strokeWidth = dp(1.4f)
            stroke.color = tint(0x90 - k * 16, 0.35f)
            c.drawPath(path, stroke)
        }
    }

    /** Sol alttan sağ üste kavisle yükselen ışık şeritleri. */
    private fun drawYoutube(c: Canvas, w: Float, h: Float) {
        for (k in 0..2) {
            path.rewind()
            val shift = h * 0.10f * k
            path.moveTo(w * (0.28f + k * 0.08f), h * 1.02f)
            path.cubicTo(w * 0.55f, h * (0.92f - k * 0.04f), w * 0.78f, h * 0.72f + shift, w * 1.02f, h * (0.26f + k * 0.14f))
            stroke.color = tint(0x24, 0.05f); stroke.strokeWidth = dp(13f - k * 3f); c.drawPath(path, stroke)
            stroke.color = tint(0x66, 0.1f); stroke.strokeWidth = dp(5f - k); c.drawPath(path, stroke)
            stroke.color = tint(0xF0 - k * 50, 0.45f); stroke.strokeWidth = dp(1.6f); c.drawPath(path, stroke)
        }
    }

    /** Ortadan iki yana yayılan ses dalgası. */
    private fun drawRadio(c: Canvas, w: Float, h: Float) {
        val bars = 34
        val left = w * 0.36f
        val gap = (w - left) / bars
        val mid = h * 0.47f
        val rnd = Random(7)
        stroke.strokeWidth = gap * 0.42f
        for (i in 0 until bars) {
            val env = sin(i / (bars - 1f) * Math.PI.toFloat())
            val bh = h * 0.38f * env * (0.35f + 0.65f * rnd.nextFloat()) + dp(1f)
            val x = left + gap * (i + 0.5f)
            stroke.shader = LinearGradient(0f, mid - bh, 0f, mid + bh,
                intArrayOf(tint(0x30, 0.2f), tint(0xF0, 0.25f), tint(0x30, 0.2f)), null, Shader.TileMode.CLAMP)
            c.drawLine(x, mid - bh, x, mid + bh, stroke)
        }
        stroke.shader = null
    }

    /** Sağ altta parlak kenarlı dünya ve üzerinde birbirine bağlı şehir ışıkları. */
    private fun drawWeb(c: Canvas, w: Float, h: Float) {
        val r = w * 0.72f
        val cx = w * 0.92f
        val cy = h * 1.32f
        fill.shader = RadialGradient(cx, cy, r, intArrayOf(0xFF030A18.toInt(), tint(0xD0).let { blend(it, Color.BLACK, 0.6f) }),
            floatArrayOf(0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, fill)
        fill.shader = null
        stroke.color = tint(0x30, 0.2f); stroke.strokeWidth = dp(11f); c.drawCircle(cx, cy, r, stroke)
        stroke.color = tint(0x70, 0.35f); stroke.strokeWidth = dp(4f); c.drawCircle(cx, cy, r, stroke)
        stroke.color = tint(0xE0, 0.7f); stroke.strokeWidth = dp(1.3f); c.drawCircle(cx, cy, r, stroke)

        val rnd = Random(11)
        var lastX = 0f
        var lastY = 0f
        stroke.strokeWidth = dp(0.7f)
        repeat(46) { i ->
            val a = Math.toRadians((185 + rnd.nextInt(80)).toDouble())
            val d = r * (0.62f + rnd.nextFloat() * 0.35f)
            val x = cx + d * kotlin.math.cos(a).toFloat()
            val y = cy + d * sin(a).toFloat()
            fill.color = tint(0x90 + rnd.nextInt(0x60), 0.75f)
            c.drawCircle(x, y, dp(0.6f + rnd.nextFloat() * 1.1f), fill)
            if (i % 3 == 0 && i > 0 && abs(x - lastX) < w * 0.18f) {
                stroke.color = tint(0x60, 0.6f)
                c.drawLine(lastX, lastY, x, y, stroke)
            }
            lastX = x; lastY = y
        }
    }

    /** Gece gökyüzü, ay ve üst üste sıradağlar. */
    private fun drawWeather(c: Canvas, w: Float, h: Float) {
        val mx = w * 0.84f
        val my = h * 0.28f
        val mr = h * 0.075f
        fill.shader = RadialGradient(mx, my, mr * 4.5f, intArrayOf(0x55DCE6F5, 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr * 4.5f, fill)
        fill.shader = null
        fill.color = 0xFFC8D0DE.toInt()
        c.drawCircle(mx, my, mr, fill)
        val rnd = Random(3)
        repeat(24) {
            fill.color = Color.argb(60 + rnd.nextInt(130), 255, 255, 255)
            c.drawCircle(w * (0.25f + rnd.nextFloat() * 0.75f), h * rnd.nextFloat() * 0.45f, dp(0.5f + rnd.nextFloat() * 0.6f), fill)
        }
        ridge(c, w, h, 0.62f, 0.18f, 21, 0xFF2A3342.toInt())
        ridge(c, w, h, 0.74f, 0.14f, 33, 0xFF171D28.toInt())
        ridge(c, w, h, 0.88f, 0.08f, 57, 0xFF0A0D13.toInt())
    }

    private fun ridge(c: Canvas, w: Float, h: Float, base: Float, height: Float, seed: Int, col: Int) {
        val rnd = Random(seed)
        path.rewind()
        path.moveTo(0f, h)
        val steps = 22
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            // Sağa doğru yükselen, sivri tepeli sıra
            val y = h * (base - height * (0.3f + 0.7f * t) * (if (i % 2 == 0) rnd.nextFloat() else 0.25f + 0.75f * rnd.nextFloat()))
            path.lineTo(w * t, y)
        }
        path.lineTo(w, h)
        path.close()
        fill.color = col
        c.drawPath(path, fill)
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

/** Uygulamalar sayfasının zemini: koyu renk üzerinde tema renginde çapraz ışık çizgileri. */
class StreakBackground(private val base: Int, private val accent: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        fill.shader = LinearGradient(0f, 0f, 0f, h, Palette.BG_TOP.takeIf { base == 0 } ?: base,
            Palette.BG_BOTTOM.takeIf { base == 0 } ?: base, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = RadialGradient(w * 0.56f, 0f, w * 0.34f, Palette.withAlpha(accent, 0x30), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = RadialGradient(w * 0.22f, h, w * 0.26f, Palette.withAlpha(accent, 0x22), 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null
        streak(canvas, w * 0.575f, 0f, w * 0.53f, h * 0.13f)
        streak(canvas, w * 0.60f, 0f, w * 0.565f, h * 0.10f)
        streak(canvas, w * 0.245f, h * 0.93f, w * 0.21f, h)
        canvas.restore()
    }

    private fun streak(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        paint.shader = LinearGradient(x1, y1, x2, y2, Palette.withAlpha(accent, 0xE0), Palette.withAlpha(accent, 0x10), Shader.TileMode.CLAMP)
        paint.strokeWidth = density * 1.6f
        c.drawLine(x1, y1, x2, y2, paint)
        paint.shader = null
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
