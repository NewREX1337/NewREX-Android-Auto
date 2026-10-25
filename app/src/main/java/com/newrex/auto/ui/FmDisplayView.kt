// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.newrex.auto.data.FmPresets
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * FM sayfasındaki büyük frekans göstergesi. [style] ile 8 görünüm: lambalı (nixie), araç radyosu LCD'si,
 * mavi-yeşil VFD ekran, neon, eski radyo kadranı, ibreli gösterge, kırmızı LED ve sade.
 */
class FmDisplayView(context: Context, style: Int = 0) : View(context) {
    data class Style(val name: String, val pointer: Int)

    companion object {
        const val ORANGE = 0xFFFF7A1A.toInt()
        const val ORANGE_LIGHT = 0xFFFFB066.toInt()
        private const val AMBER = 0xFFFFB238.toInt()
        private const val CYAN = 0xFF3DF5D0.toInt()
        private const val RED = 0xFFFF3B30.toInt()

        /** pointer = 0: tema rengi. */
        val STYLES = listOf(
            Style("Lambalı", ORANGE),
            Style("LCD", AMBER),
            Style("VFD", CYAN),
            Style("Neon", 0),
            Style("Kadran", RED),
            Style("İbreli", ORANGE),
            Style("LED", RED),
            Style("Sade", 0),
        )

        // 5x7 noktalı yazı tipi (her satır 5 bit)
        private val DOTS = mapOf(
            '0' to intArrayOf(14, 17, 19, 21, 25, 17, 14), '1' to intArrayOf(4, 12, 4, 4, 4, 4, 14),
            '2' to intArrayOf(14, 17, 1, 2, 4, 8, 31), '3' to intArrayOf(31, 2, 4, 2, 1, 17, 14),
            '4' to intArrayOf(2, 6, 10, 18, 31, 2, 2), '5' to intArrayOf(31, 16, 30, 1, 1, 17, 14),
            '6' to intArrayOf(6, 8, 16, 30, 17, 17, 14), '7' to intArrayOf(31, 1, 2, 4, 8, 8, 8),
            '8' to intArrayOf(14, 17, 17, 14, 17, 17, 14), '9' to intArrayOf(14, 17, 17, 15, 1, 2, 12),
            '.' to intArrayOf(0, 0, 0, 0, 0, 12, 12), ' ' to IntArray(7),
        )
    }

    var style = style.coerceIn(0, STYLES.lastIndex)
        set(v) { field = v.coerceIn(0, STYLES.lastIndex); invalidate() }

    var freq = 1000
        set(v) { field = v; invalidate() }

    /** Skaladaki ibrenin rengi bu görünüme uysun diye. */
    val pointerColor: Int get() = STYLES[style].pointer.takeIf { it != 0 } ?: Palette.accent

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE); strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val label get() = FmPresets.format(freq)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        when (style) {
            0 -> drawNixie(canvas, w, h)
            1 -> drawLcd(canvas, w, h)
            2 -> drawVfd(canvas, w, h)
            3 -> drawNeon(canvas, w, h)
            4 -> drawDial(canvas, w, h)
            5 -> drawMeter(canvas, w, h)
            6 -> drawLed(canvas, w, h)
            7 -> drawPlain(canvas, w, h)
        }
    }

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface,
                    align: Paint.Align = Paint.Align.CENTER, glow: Float = 0f, glowColor: Int = color, spacing: Float = 0f) {
        text.textSize = size
        text.typeface = tf
        text.color = color
        text.textAlign = align
        text.letterSpacing = spacing
        if (glow > 0f) text.setShadowLayer(glow, 0f, 0f, glowColor) else text.clearShadowLayer()
        c.drawText(s, x, y, text)
    }

    /** Ekranın ortasında, oranı korunmuş bir panel alanı. */
    private fun panelRect(w: Float, h: Float, aspect: Float, fill: Float = 0.96f): RectF {
        var pw = w * fill
        var ph = pw / aspect
        if (ph > h * fill) { ph = h * fill; pw = ph * aspect }
        return RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2)
    }

    /** 7 parçalı rakamlarla "100.0" yazar; boş haneye soluk "8" konur. */
    private fun segmentText(c: Canvas, area: RectF, on: Int, ghost: Int, glow: Float) {
        val s = label.padStart(5, ' ')
        val dh = area.height()
        val dw = dh * 0.5f
        val gap = dw * 0.3f
        val dot = dw * 0.35f
        var x = area.centerX() - (4 * dw + 4 * gap + dot) / 2
        for (ch in s) {
            if (ch == '.') {
                fill.color = on
                fill.setShadowLayer(glow, 0f, 0f, on)
                c.drawCircle(x + dot / 2 - gap / 2, area.bottom - dw * 0.08f, dw * 0.09f, fill)
                fill.clearShadowLayer()
                x += dot
                continue
            }
            Segments.draw(c, fill, '8', x, area.top, dw, dh, ghost, 0f)
            if (ch != ' ') Segments.draw(c, fill, ch, x, area.top, dw, dh, on, glow)
            x += dw + gap
        }
    }

    // ---- Görünümler ----

    private fun drawNixie(c: Canvas, w: Float, h: Float) {
        val s = label.padStart(5, ' ')   // "100.0" / " 87.5"
        val tubeH = h * 0.92f
        var tubeW = tubeH * 0.56f
        if (tubeW * 4.9f > w) tubeW = w / 4.9f
        val gap = tubeW * 0.12f
        val dotW = tubeW * 0.35f
        val total = 4 * tubeW + dotW + 4 * gap
        var x = (w - total) / 2f
        val top = (h - tubeH) / 2f
        for (ch in s) {
            val isDot = ch == '.'
            val tw = if (isDot) dotW else tubeW
            tube(c, x, top, tw, tubeH, isDot)
            val cx = x + tw / 2
            if (isDot) {
                fill.color = ORANGE_LIGHT
                fill.setShadowLayer(tw * 0.4f, 0f, 0f, ORANGE)
                c.drawCircle(cx, top + tubeH * 0.78f, tw * 0.13f, fill)
                fill.clearShadowLayer()
            } else {
                val size = min(tubeH * 0.62f, tubeW * 1.05f)
                txt(c, "8", cx, top + tubeH * 0.72f, size, 0x14FFB070, thin)
                if (ch != ' ') txt(c, ch.toString(), cx, top + tubeH * 0.72f, size, ORANGE_LIGHT, light, glow = size * 0.18f, glowColor = ORANGE)
            }
            x += tw + gap
        }
    }

    /** Cam tüp: koyu, üstte yansıması olan yuvarlak gövde. */
    private fun tube(c: Canvas, x: Float, y: Float, w: Float, h: Float, small: Boolean) {
        val r = w / 2
        val top = if (small) y + h * 0.55f else y
        rect.set(x, top, x + w, y + h)
        fill.shader = LinearGradient(x, 0f, x + w, 0f,
            intArrayOf(0x40FFFFFF, 0x0CFFFFFF, 0x06FFFFFF, 0x26FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, fill)
        fill.shader = null
        line.strokeWidth = w * 0.025f
        line.color = 0x33FFFFFF
        c.drawRoundRect(rect, r, r, line)
        fill.color = 0xFF14161C.toInt()
        c.drawRect(x + w * 0.1f, y + h - h * 0.05f, x + w * 0.9f, y + h, fill)
    }

    /** Eski araç radyosu: turuncu arka ışıklı LCD, koyu rakamlar ve göstergeler. */
    private fun drawLcd(c: Canvas, w: Float, h: Float) {
        val p = panelRect(w, h, 3.1f)
        val rr = p.height() * 0.08f
        fill.shader = LinearGradient(0f, p.top, 0f, p.bottom, 0xFFFFC765.toInt(), 0xFFE58F1C.toInt(), Shader.TileMode.CLAMP)
        fill.setShadowLayer(p.height() * 0.15f, 0f, 0f, 0x88FF9A1A.toInt())
        c.drawRoundRect(p, rr, rr, fill)
        fill.clearShadowLayer(); fill.shader = null
        line.strokeWidth = p.height() * 0.04f
        line.color = 0xFF2A2420.toInt()
        c.drawRoundRect(p, rr, rr, line)
        val ink = 0xE61F140A.toInt()
        val digits = RectF(p.left + p.width() * 0.16f, p.top + p.height() * 0.2f, p.right - p.width() * 0.12f, p.bottom - p.height() * 0.2f)
        segmentText(c, digits, ink, 0x1A1F140A, 0f)
        val ts = p.height() * 0.13f
        txt(c, "FM1", p.left + p.width() * 0.04f, p.top + p.height() * 0.32f, ts, ink, condensed, Paint.Align.LEFT)
        txt(c, "ST", p.left + p.width() * 0.04f, p.top + p.height() * 0.55f, ts, ink, condensed, Paint.Align.LEFT)
        txt(c, "MHz", p.right - p.width() * 0.03f, p.bottom - p.height() * 0.2f, ts, ink, condensed, Paint.Align.RIGHT)
        txt(c, "TP", p.right - p.width() * 0.03f, p.top + p.height() * 0.32f, ts, ink, condensed, Paint.Align.RIGHT)
    }

    /** Vakumlu floresan ekran: siyah cam, mavi-yeşil parlayan parçalar, nokta ızgarası. */
    private fun drawVfd(c: Canvas, w: Float, h: Float) {
        val p = panelRect(w, h, 3.2f)
        val rr = p.height() * 0.1f
        fill.color = 0xF0060A0C.toInt()
        c.drawRoundRect(p, rr, rr, fill)
        fill.color = 0x10_3DF5D0
        val step = p.height() * 0.06f
        var yy = p.top + step
        while (yy < p.bottom) {
            var xx = p.left + step
            while (xx < p.right) { c.drawCircle(xx, yy, step * 0.12f, fill); xx += step }
            yy += step
        }
        val digits = RectF(p.left + p.width() * 0.08f, p.top + p.height() * 0.18f, p.right - p.width() * 0.16f, p.bottom - p.height() * 0.18f)
        segmentText(c, digits, CYAN, 0x143DF5D0, p.height() * 0.06f)
        val ts = p.height() * 0.12f
        txt(c, "MHz", p.right - p.width() * 0.04f, p.bottom - p.height() * 0.2f, ts, CYAN, medium, Paint.Align.RIGHT, glow = ts * 0.4f)
        rect.set(p.right - p.width() * 0.15f, p.top + p.height() * 0.16f, p.right - p.width() * 0.04f, p.top + p.height() * 0.34f)
        line.strokeWidth = ts * 0.1f; line.color = CYAN
        c.drawRoundRect(rect, ts * 0.3f, ts * 0.3f, line)
        txt(c, "STEREO", rect.centerX(), rect.bottom - rect.height() * 0.28f, ts * 0.62f, CYAN, medium)
        // Cam yansıması
        fill.shader = LinearGradient(0f, p.top, 0f, p.centerY(), 0x14FFFFFF, 0, Shader.TileMode.CLAMP)
        c.drawRoundRect(p, rr, rr, fill)
        fill.shader = null
    }

    private fun drawNeon(c: Canvas, w: Float, h: Float) {
        val color = Palette.accent
        var size = h * 0.8f
        text.textSize = size; text.typeface = light
        val tw = text.measureText(label)
        if (tw > w * 0.8f) size *= w * 0.8f / tw
        text.style = Paint.Style.STROKE
        text.strokeWidth = size * 0.02f
        txt(c, label, w / 2, h * 0.5f + size * 0.36f, size, color, light, glow = size * 0.12f)
        text.style = Paint.Style.FILL
        txt(c, label, w / 2, h * 0.5f + size * 0.36f, size, 0x26FFFFFF, light)
        text.textSize = size
        val half = text.measureText(label) / 2
        txt(c, "MHz", w / 2 + half + size * 0.06f, h * 0.5f + size * 0.36f, size * 0.18f, color, medium, Paint.Align.LEFT, glow = size * 0.05f)
        txt(c, "FM", w / 2 - half - size * 0.06f, h * 0.5f - size * 0.18f, size * 0.18f, color, medium, Paint.Align.RIGHT, glow = size * 0.05f)
    }

    /** 70'lerin radyosu: krem renkli, arkadan aydınlatılmış skala ve kırmızı ibre. */
    private fun drawDial(c: Canvas, w: Float, h: Float) {
        val p = panelRect(w, h, 3.6f)
        val rr = p.height() * 0.12f
        fill.shader = LinearGradient(0f, p.top, 0f, p.bottom, 0xFF3B2A1C.toInt(), 0xFF22170F.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(p, rr, rr, fill)
        val inner = RectF(p.left + p.height() * 0.08f, p.top + p.height() * 0.08f, p.right - p.height() * 0.08f, p.bottom - p.height() * 0.08f)
        fill.shader = RadialGradient(inner.centerX(), inner.centerY(), inner.width() * 0.6f,
            0xFFF6E7C1.toInt(), 0xFFD9BE86.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(inner, rr * 0.6f, rr * 0.6f, fill)
        fill.shader = null
        val ink = 0xFF3A2A18.toInt()
        val left = inner.left + inner.width() * 0.05f
        val right = inner.right - inner.width() * 0.05f
        fun xOf(f: Int) = left + (right - left) * (f - FmPresets.MIN) / (FmPresets.MAX - FmPresets.MIN).toFloat()
        val base = inner.top + inner.height() * 0.62f
        for (f in 880..1080 step 5) {
            val major = f % 20 == 0
            line.strokeWidth = inner.height() * if (major) 0.025f else 0.012f
            line.color = ink
            c.drawLine(xOf(f), base - inner.height() * if (major) 0.22f else 0.11f, xOf(f), base, line)
            if (major) txt(c, (f / 10).toString(), xOf(f), inner.top + inner.height() * 0.28f, inner.height() * 0.17f, ink, condensed)
        }
        line.strokeWidth = inner.height() * 0.015f
        c.drawLine(left, base, right, base, line)
        txt(c, "FM  MHz", inner.left + inner.width() * 0.02f, inner.bottom - inner.height() * 0.1f, inner.height() * 0.12f, ink, medium, Paint.Align.LEFT, spacing = 0.2f)
        txt(c, label, inner.right - inner.width() * 0.02f, inner.bottom - inner.height() * 0.08f, inner.height() * 0.2f, ink, condensed, Paint.Align.RIGHT)
        val nx = xOf(freq)
        line.strokeWidth = inner.height() * 0.035f
        line.color = RED
        line.setShadowLayer(inner.height() * 0.06f, 0f, 0f, 0xAAFF3B30.toInt())
        c.drawLine(nx, inner.top + inner.height() * 0.06f, nx, inner.bottom - inner.height() * 0.06f, line)
        line.clearShadowLayer()
        // Cam
        fill.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawRoundRect(inner, rr * 0.6f, rr * 0.6f, fill)
        fill.shader = null
    }

    /** Yarım daire skala, ibre ve altında dijital okuma. */
    private fun drawMeter(c: Canvas, w: Float, h: Float) {
        val r = min(w * 0.42f, h * 0.82f)
        val cx = w / 2
        val cy = h / 2 + r * 0.47f
        val start = 200f
        val sweep = 140f
        fun ang(f: Int) = start + sweep * (f - FmPresets.MIN) / (FmPresets.MAX - FmPresets.MIN).toFloat()
        rect.set(cx - r, cy - r, cx + r, cy + r)
        line.strokeWidth = r * 0.04f
        line.color = 0x33FFFFFF
        c.drawArc(rect, start, sweep, false, line)
        line.color = ORANGE
        line.setShadowLayer(r * 0.05f, 0f, 0f, ORANGE)
        c.drawArc(rect, start, ang(freq) - start, false, line)
        line.clearShadowLayer()
        for (f in 880..1080 step 10) {
            val major = f % 40 == 0
            val a = Math.toRadians(ang(f).toDouble())
            val r1 = r * 0.9f
            val r2 = r * if (major) 0.78f else 0.84f
            line.strokeWidth = r * if (major) 0.02f else 0.01f
            line.color = if (major) 0xCCFFFFFF.toInt() else 0x66FFFFFF
            c.drawLine(cx + cos(a).toFloat() * r1, cy + sin(a).toFloat() * r1, cx + cos(a).toFloat() * r2, cy + sin(a).toFloat() * r2, line)
            if (major) txt(c, (f / 10).toString(), cx + cos(a).toFloat() * r * 0.66f, cy + sin(a).toFloat() * r * 0.66f + r * 0.04f,
                r * 0.1f, Palette.TEXT_DIM, medium)
        }
        val a = Math.toRadians(ang(freq).toDouble())
        line.strokeWidth = r * 0.025f
        line.color = ORANGE_LIGHT
        line.setShadowLayer(r * 0.04f, 0f, 0f, ORANGE)
        c.drawLine(cx, cy, cx + cos(a).toFloat() * r * 0.88f, cy + sin(a).toFloat() * r * 0.88f, line)
        line.clearShadowLayer()
        fill.color = ORANGE
        c.drawCircle(cx, cy, r * 0.05f, fill)
        txt(c, label, cx, cy - r * 0.18f, r * 0.3f, Palette.TEXT, light)
        txt(c, "MHz", cx, cy - r * 0.06f, r * 0.08f, Palette.TEXT_DIM, medium, spacing = 0.3f)
    }

    /** Kırmızı nokta-matris LED ekran. */
    private fun drawLed(c: Canvas, w: Float, h: Float) {
        val p = panelRect(w, h, 3.3f)
        val rr = p.height() * 0.08f
        fill.color = 0xF00A0505.toInt()
        c.drawRoundRect(p, rr, rr, fill)
        line.strokeWidth = p.height() * 0.02f
        line.color = 0x33FF3B30
        c.drawRoundRect(p, rr, rr, line)
        val s = label.padStart(5, ' ') + "  "
        val cols = s.length * 6 - 1
        val cell = min(p.width() * 0.9f / cols, p.height() * 0.72f / 7)
        var x = p.centerX() - cols * cell / 2
        val top = p.centerY() - 7 * cell / 2
        val dot = cell * 0.38f
        for (ch in s) {
            val rows = DOTS[ch] ?: IntArray(7)
            for (ry in 0 until 7) for (cx in 0 until 5) {
                val on = rows[ry] shr (4 - cx) and 1 == 1
                fill.color = if (on) RED else 0x22FF3B30
                if (on) fill.setShadowLayer(dot * 1.2f, 0f, 0f, RED) else fill.clearShadowLayer()
                c.drawCircle(x + cx * cell + cell / 2, top + ry * cell + cell / 2, dot, fill)
            }
            x += 6 * cell
        }
        fill.clearShadowLayer()
        txt(c, "MHz", p.right - p.width() * 0.04f, top + 7 * cell, cell * 2f, RED, condensed, Paint.Align.RIGHT, glow = cell)
    }

    /** Büyük ince rakamlar ve altında bant üzerindeki konum çizgisi. */
    private fun drawPlain(c: Canvas, w: Float, h: Float) {
        val color = Palette.accent
        var size = h * 0.78f
        text.textSize = size; text.typeface = thin
        val tw = text.measureText(label)
        if (tw > w * 0.78f) size *= w * 0.78f / tw
        val base = h * 0.5f + size * 0.3f
        txt(c, label, w / 2, base, size, Palette.TEXT, thin)
        text.textSize = size
        val half = text.measureText(label) / 2
        txt(c, "MHz", w / 2 + half + size * 0.05f, base, size * 0.16f, Palette.TEXT_DIM, medium, Paint.Align.LEFT)
        val left = w / 2 - half
        val right = w / 2 + half
        val y = base + size * 0.14f
        line.strokeWidth = size * 0.012f
        line.color = 0x33FFFFFF
        c.drawLine(left, y, right, y, line)
        val p = (freq - FmPresets.MIN) / (FmPresets.MAX - FmPresets.MIN).toFloat()
        line.color = color
        line.strokeWidth = size * 0.024f
        c.drawLine(left, y, left + (right - left) * p, y, line)
        fill.color = color
        fill.setShadowLayer(size * 0.04f, 0f, 0f, color)
        c.drawCircle(left + (right - left) * p, y, size * 0.03f, fill)
        fill.clearShadowLayer()
    }
}
