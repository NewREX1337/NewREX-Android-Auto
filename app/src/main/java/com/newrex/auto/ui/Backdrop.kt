// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.newrex.auto.t
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hazır arka planlar. Hareketli dalga (0) dışındakiler bir kez resme çizilir ve sabit durur: her karede yeniden
 * çizilmedikleri için telefonu yormazlar. Renkler tema renginden türetilir, her tema rengiyle uyumlu görünür.
 */
object Backdrop {
    val NAMES get() = listOf(t("Dalga"), t("Kuzey ışıkları"), t("Gece yolu"), t("Bokeh"), t("Yıldızlar"), t("İpek"), t("Dağlar"), t("Degrade"))

    private val thumbs = HashMap<String, Bitmap>()

    /** Ayarlardaki küçük önizlemeler: her dokunuşta 8 resmi baştan çizmemek için saklanır. */
    fun thumb(style: Int, w: Int, h: Int, accent: Int): Bitmap {
        val key = "$style/$w/$h/$accent"
        thumbs[key]?.let { return it }
        if (thumbs.size > 24) thumbs.clear()
        return render(style, w, h, accent).also { thumbs[key] = it }
    }

    fun render(style: Int, w: Int, h: Int, accent: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val a2 = Palette.companion(accent)
        val fw = w.toFloat(); val fh = h.toFloat()
        when (style) {
            1 -> aurora(c, fw, fh, accent, a2)
            2 -> synthwave(c, fw, fh, accent, a2)
            3 -> bokeh(c, fw, fh, accent, a2)
            4 -> stars(c, fw, fh, accent, a2)
            5 -> silk(c, fw, fh, accent, a2)
            6 -> mountains(c, fw, fh, accent, a2)
            else -> gradient(c, fw, fh, accent, a2)
        }
        return bmp
    }

    // ---- Yardımcılar ----

    private fun mix(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
        (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
        (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt())

    private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    /** Rengin çok koyu hali (zemin). */
    private fun deep(c: Int, v: Float = 0.10f): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = (hsv[1] * 0.8f).coerceAtMost(0.7f); hsv[2] = v
        return Color.HSVToColor(hsv)
    }

    private fun base(c: Canvas, w: Float, h: Float, top: Int, bottom: Int) {
        c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, top, bottom, Shader.TileMode.CLAMP) })
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, color: Int, a: Int) {
        c.drawCircle(x, y, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(x, y, r, intArrayOf(alpha(color, a), alpha(color, a / 3), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private fun vignette(c: Canvas, w: Float, h: Float) {
        c.drawRect(0f, 0f, w, h, Paint().apply {
            shader = RadialGradient(w / 2, h / 2, maxOf(w, h) * 0.75f, intArrayOf(0, 0, 0x99000000.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
    }

    // ---- Stiller ----

    /** Kuzey ışıkları: koyu gökte tema renginde dalgalanan ışık perdeleri. */
    private fun aurora(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, deep(a1, 0.08f), Color.BLACK)
        glow(c, w * 0.2f, h * 0.15f, w * 0.5f, a2, 0x40)
        glow(c, w * 0.85f, h * 0.3f, w * 0.45f, a1, 0x38)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val curtains = listOf(Triple(0.35f, a1, 0.9f), Triple(0.5f, a2, 1.3f), Triple(0.42f, mix(a1, Color.WHITE, 0.3f), 2.1f))
        for ((level, col, ph) in curtains) {
            for (k in 0 until 26) {
                val path = Path()
                val off = k * h * 0.012f
                for (i in 0..60) {
                    val x = w * i / 60f
                    val y = h * level + sin(i / 60f * 2.6f * PI.toFloat() + ph) * h * 0.12f + off
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                p.strokeWidth = h * 0.02f
                p.maskFilter = BlurMaskFilter(h * 0.03f, BlurMaskFilter.Blur.NORMAL)
                p.color = alpha(col, (0x30 * (1f - k / 26f)).toInt())
                c.drawPath(path, p)
            }
        }
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(7)
        repeat(120) {
            star.color = alpha(Color.WHITE, 40 + r.nextInt(120))
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.6f, 0.6f + r.nextFloat() * 1.2f, star)
        }
        vignette(c, w, h)
    }

    /** Gece yolu: ufukta çizgili güneş, perspektif ızgara zemin (retro / synthwave). */
    private fun synthwave(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        val horizon = h * 0.62f
        base(c, w, horizon, deep(a2, 0.10f), mix(deep(a1, 0.35f), a1, 0.25f))
        c.drawRect(0f, horizon, w, h, Paint().apply { color = deep(a1, 0.06f) })
        val sunR = h * 0.24f
        val sx = w * 0.5f
        val sun = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, horizon - sunR * 2, 0f, horizon, mix(a2, Color.WHITE, 0.35f), a1, Shader.TileMode.CLAMP)
        }
        glow(c, sx, horizon - sunR * 0.6f, sunR * 2.4f, a1, 0x55)
        c.save()
        c.clipRect(0f, 0f, w, horizon)
        c.drawCircle(sx, horizon - sunR * 0.55f, sunR, sun)
        // Güneşin alt yarısında koyu çizgiler
        val cut = Paint().apply { color = deep(a1, 0.30f) }
        for (i in 0 until 6) {
            val y = horizon - sunR * 0.55f + i * sunR * 0.17f
            c.drawRect(sx - sunR, y, sx + sunR, y + sunR * (0.03f + i * 0.012f), cut)
        }
        c.restore()
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = alpha(a1, 0xB0); strokeWidth = h * 0.004f }
        val glowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = alpha(a1, 0x50); strokeWidth = h * 0.014f; maskFilter = BlurMaskFilter(h * 0.01f, BlurMaskFilter.Blur.NORMAL)
        }
        for (i in -14..14) {
            val xb = sx + i * w * 0.09f
            c.drawLine(sx + i * w * 0.012f, horizon, xb, h, glowLine)
            c.drawLine(sx + i * w * 0.012f, horizon, xb, h, line)
        }
        var y = horizon
        var step = h * 0.012f
        while (y < h) {
            c.drawLine(0f, y, w, y, glowLine); c.drawLine(0f, y, w, y, line)
            y += step; step *= 1.32f
        }
        c.drawRect(0f, horizon - h * 0.004f, w, horizon + h * 0.004f, Paint().apply { color = mix(a1, Color.WHITE, 0.4f) })
        vignette(c, w, h)
    }

    /** Bokeh: odak dışı şehir ışıkları gibi yumuşak daireler. */
    private fun bokeh(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, deep(a1, 0.12f), Color.BLACK)
        val r = Random(21)
        val cols = listOf(a1, a2, 0xFFFFB74D.toInt(), mix(a1, Color.WHITE, 0.5f))
        repeat(46) {
            val rad = h * (0.03f + r.nextFloat() * 0.13f)
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val col = cols[r.nextInt(cols.size)]
            c.drawCircle(x, y, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, rad, intArrayOf(alpha(col, 0x50), alpha(col, 0x38), alpha(col, 0x10)),
                    floatArrayOf(0f, 0.85f, 1f), Shader.TileMode.CLAMP)
            })
            c.drawCircle(x, y, rad, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = rad * 0.05f; color = alpha(col, 0x30)
            })
        }
        vignette(c, w, h)
    }

    /** Yıldızlı gece ve renkli bulutsu. */
    private fun stars(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, 0xFF05060C.toInt(), deep(a1, 0.08f))
        val neb = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(h * 0.18f, BlurMaskFilter.Blur.NORMAL) }
        neb.color = alpha(a1, 0x45); c.drawOval(w * 0.15f, h * 0.25f, w * 0.75f, h * 0.65f, neb)
        neb.color = alpha(a2, 0x38); c.drawOval(w * 0.45f, h * 0.05f, w * 0.95f, h * 0.5f, neb)
        val r = Random(3)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(420) {
            val x = r.nextFloat() * w; val y = r.nextFloat() * h
            val big = r.nextFloat() < 0.04f
            p.color = alpha(if (r.nextFloat() < 0.15f) mix(a2, Color.WHITE, 0.6f) else Color.WHITE, 60 + r.nextInt(170))
            c.drawCircle(x, y, if (big) 1.8f + r.nextFloat() * 1.4f else 0.5f + r.nextFloat() * 0.9f, p)
            if (big) glow(c, x, y, h * 0.02f, Color.WHITE, 0x50)
        }
        vignette(c, w, h)
    }

    /** İpek: ince çizgilerden oluşan sakin ışık şeritleri (hareketli dalganın durağan hali). */
    private fun silk(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, deep(a1, 0.13f), Color.BLACK)
        glow(c, w * 0.75f, h * 0.15f, maxOf(w, h) * 0.6f, a1, 0x30)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.3f }
        val ribbons = listOf(Triple(0.6f, a1, 0f), Triple(0.7f, a2, 2.1f), Triple(0.52f, mix(a1, Color.WHITE, 0.4f), 4.2f))
        for ((level, col, ph) in ribbons) {
            for (k in 0 until 14) {
                val spread = k / 13f - 0.5f
                val path = Path()
                for (i in 0..80) {
                    val u = i / 80f * 2 * PI.toFloat()
                    val y = h * level + h * 0.09f * sin(u * 0.9f + ph) + h * 0.045f * sin(u * 1.7f + ph * 1.3f) +
                        spread * h * 0.07f * (1f + 0.5f * sin(u + ph))
                    if (i == 0) path.moveTo(0f, y) else path.lineTo(w * i / 80f, y)
                }
                p.color = alpha(col, (0x60 * (1f - kotlin.math.abs(spread) * 1.6f).coerceAtLeast(0.15f)).toInt())
                c.drawPath(path, p)
            }
        }
        vignette(c, w, h)
    }

    /** Dağlar: alacakaranlıkta üst üste katmanlı dağ silüetleri. */
    private fun mountains(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, deep(a2, 0.18f), mix(a1, 0xFFFF8A65.toInt(), 0.35f))
        glow(c, w * 0.7f, h * 0.55f, h * 0.7f, 0xFFFFC08A.toInt(), 0x70)
        val r = Random(11)
        for (layer in 0 until 5) {
            val t = layer / 4f
            val path = Path().apply { moveTo(0f, h) }
            val baseY = h * (0.45f + t * 0.12f)
            var x = 0f
            path.lineTo(0f, baseY)
            while (x <= w) {
                val y = baseY - (sin(x / w * (3 + layer) * PI.toFloat() + layer) * 0.5f + 0.5f) * h * (0.16f - t * 0.05f) -
                    r.nextFloat() * h * 0.02f
                path.lineTo(x, y)
                x += w / 40f
            }
            path.lineTo(w, h); path.close()
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mix(deep(a1, 0.30f - t * 0.05f), Color.BLACK, t * 0.7f) })
        }
        vignette(c, w, h)
    }

    /** Degrade: tema renginden yumuşak, sade renk geçişi. */
    private fun gradient(c: Canvas, w: Float, h: Float, a1: Int, a2: Int) {
        base(c, w, h, deep(a1, 0.22f), deep(a2, 0.06f))
        glow(c, 0f, 0f, maxOf(w, h) * 0.8f, a1, 0x55)
        glow(c, w, h, maxOf(w, h) * 0.7f, a2, 0x40)
        glow(c, w * 0.6f, h * 0.4f, h * 0.6f, mix(a1, Color.WHITE, 0.3f), 0x18)
        vignette(c, w, h)
    }

}
