// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * Eski konsol menülerini andıran sakin arka plan: koyu renk geçişi, yavaşça dalgalanan ince ışık
 * şeritleri ve süzülen küçük parçacıklar. Açılışta şeritler yavaşça belirir.
 *
 * İki renk paleti tutar ([setPalette]); [mix] 0 → birinci, 1 → ikinci palet (sayfa kaydırılırken geçiş).
 */
class WaveBackground(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(1.2f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val glowPaint = Paint()
    private val base = FloatArray(STEPS + 1)
    private val wobble = FloatArray(STEPS + 1)
    private val tilt = FloatArray(STEPS + 1)
    private val path = android.graphics.Path()

    /** Kare aralığı (ms). Arka planda kalan sayfalarda daha seyrek çizilir. */
    var frameMs = 33L

    // Her palet: [koyu taban, ışık rengi 1, ışık rengi 2]
    private val palettes = Array(2) { intArrayOf(0xFF0B0E1A.toInt(), Palette.accent, Palette.accent) }
    private var shown = IntArray(3)
    private var shaderKey = 0L

    var mix = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** false iken animasyon durur (ör. tam ekran video oynarken işlemciyi yormasın). */
    var running = true
        set(v) { field = v; if (v) invalidate() }

    private val frame = Runnable { invalidate() }
    private val start = SystemClock.uptimeMillis()
    private val particles = List(34) { i ->
        val r = java.util.Random(i * 7919L)
        floatArrayOf(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat(), r.nextFloat() * 6.28f)
    }

    /** Paletin rengini yumuşak bir geçişle değiştirir. [glow1]/[glow2] şerit renkleri. */
    fun setPalette(index: Int, glow1: Int, glow2: Int, animate: Boolean = true, base: Int = 0) {
        val target = intArrayOf(if (base != 0) base else deep(glow1), glow1, glow2)
        val from = palettes[index].copyOf()
        if (!animate) { palettes[index] = target; invalidate(); return }
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                palettes[index] = IntArray(3) { blend(from[it], target[it], t) }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        for (i in 0..2) shown[i] = blend(palettes[0][i], palettes[1][i], mix)
        val (base, g1, g2) = Triple(shown[0], shown[1], shown[2])

        // Arka plan geçişi (renkler değişmedikçe yeniden oluşturulmaz)
        val key = base.toLong() * 31 + g1 + width * 7L + height
        if (key != shaderKey) {
            shaderKey = key
            bgPaint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(base, blend(base, 0xFF000000.toInt(), 0.55f)),
                null, Shader.TileMode.CLAMP)
            glowPaint.shader = RadialGradient(w * 0.75f, h * 0.15f, maxOf(w, h) * 0.7f,
                intArrayOf(Palette.withAlpha(g1, 0x38), 0), null, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        val t = (SystemClock.uptimeMillis() - start) / 1000f
        // Açılış: şeritler ilk 3 saniyede genişleyerek belirir
        val intro = (t / 3f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }

        drawRibbon(canvas, w, h, t, intro, 0.62f, g1, 1f, 0f)
        drawRibbon(canvas, w, h, t, intro, 0.70f, g2, -0.8f, 2.1f)
        drawRibbon(canvas, w, h, t, intro, 0.55f, blend(g1, Color.WHITE, 0.4f), 0.6f, 4.2f)

        for (p in particles) {
            val x = ((p[0] + t * 0.004f * p[2]) % 1f) * w
            val y = (p[1] * 0.9f + 0.05f) * h + sin(t * 0.3f + p[3]) * context.dpf(10)
            val tw = 0.5f + 0.5f * sin(t * 0.8f * p[2] + p[3])
            dotPaint.color = Palette.withAlpha(blend(g2, Color.WHITE, 0.6f), (tw * 0x70 * intro).toInt())
            canvas.drawCircle(x, y, context.dpf(1f + p[2]), dotPaint)
        }

        // Tek bir zamanlayıcı: ek invalidate çağrıları kare sayısını katlamasın
        removeCallbacks(frame)
        if (running) postDelayed(frame, frameMs)
    }

    /** Birbirine çok yakın, hafif kayan ince çizgilerden oluşan tek bir ışık şeridi. */
    private fun drawRibbon(
        canvas: Canvas, w: Float, h: Float, t: Float, intro: Float,
        level: Float, color: Int, speed: Float, phase: Float,
    ) {
        val amp = h * 0.09f * intro
        // Dalga şekli tüm çizgiler için bir kez hesaplanır; çizgiler sadece kaydırılmış kopyalarıdır
        for (s in 0..STEPS) {
            val u = s.toFloat() / STEPS * 2f * PI.toFloat()
            base[s] = h * level + amp * sin(u * 0.9f + t * 0.22f * speed + phase) +
                amp * 0.5f * sin(u * 1.7f - t * 0.15f * speed + phase * 1.3f)
            wobble[s] = 1f + 0.5f * sin(u + t * 0.1f)
            tilt[s] = amp * 0.9f * kotlin.math.cos(u * 0.9f + t * 0.22f * speed + phase)
        }
        for (k in 0 until LINES) {
            val spread = k / (LINES - 1f) - 0.5f
            path.rewind()
            for (s in 0..STEPS) {
                val y = base[s] + spread * (h * 0.06f * wobble[s] + tilt[s] * 0.12f)
                if (s == 0) path.moveTo(0f, y) else path.lineTo(w * s / STEPS, y)
            }
            // Ortadaki çizgiler daha parlak, kenarlara doğru söner
            val a = (0x46 * (1f - kotlin.math.abs(spread) * 1.6f).coerceAtLeast(0.15f) * intro).toInt()
            linePaint.color = Palette.withAlpha(color, a)
            // Tek parça çizgi: parçalı çizimde birleşim yerlerinde parlak noktalar oluşuyordu
            canvas.drawPath(path, linePaint)
        }
    }

    private companion object {
        const val STEPS = 40
        const val LINES = 12

        fun blend(a: Int, b: Int, t: Float): Int {
            val u = 1f - t
            return Color.argb(
                (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
                (Color.red(a) * u + Color.red(b) * t).toInt(),
                (Color.green(a) * u + Color.green(b) * t).toInt(),
                (Color.blue(a) * u + Color.blue(b) * t).toInt(),
            )
        }

        /** Işık renginden türetilmiş, çok koyu ve sakin taban rengi. */
        fun deep(c: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            hsv[1] = (hsv[1] * 0.7f).coerceAtMost(0.65f)
            hsv[2] = 0.16f
            return Color.HSVToColor(hsv)
        }
    }
}
