// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Karşılama ekranındaki saat. [style] ile 12 farklı görünüm: dijital olanlar ve yuvarlak (akrep-yelkovan)
 * olanlar. Her şey kodla çizilir; görünüm kutusuna oranı bozulmadan sığar. Saniyesi olanlar saniyede bir,
 * diğerleri dakikada bir yeniden çizilir; ekranda görünmüyorsa hiç çizilmez.
 */
class ClockView(context: Context, style: Int = 0) : View(context) {
    data class Style(val name: String, val aspect: Float, val analog: Boolean, val seconds: Boolean, val heightDp: Int)

    companion object {
        val STYLES = listOf(
            Style("İnce", 2.3f, false, false, 125),
            Style("Kalın", 2.3f, false, false, 125),
            Style("Dikey", 0.82f, false, false, 230),
            Style("LCD", 2.7f, false, true, 110),
            Style("Kart", 2.2f, false, false, 120),
            Style("Neon", 2.4f, false, false, 120),
            Style("Yazıyla", 1.75f, false, false, 165),
            Style("Klasik", 1f, true, true, 230),
            Style("Sade", 1f, true, false, 220),
            Style("Roma", 1f, true, true, 230),
            Style("Spor", 1f, true, true, 230),
            Style("Halka", 1f, true, true, 220),
        )

        /** Karşılama ekranındaki boyut (küçük/orta/büyük) için piksel ölçüsü. */
        fun sizeFor(style: Int, size: Int, scale: Float, density: Float): Pair<Int, Int> {
            val st = STYLES[style.coerceIn(0, STYLES.lastIndex)]
            val h = st.heightDp * floatArrayOf(0.72f, 1f, 1.3f)[size.coerceIn(0, 2)] * scale * density
            return (h * st.aspect).toInt() to h.toInt()
        }

        private val HOURS = arrayOf("on iki", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on", "on bir")
        private val HOURS_ACC = arrayOf("on ikiyi", "biri", "ikiyi", "üçü", "dördü", "beşi", "altıyı", "yediyi", "sekizi", "dokuzu", "onu", "on biri")
        private val HOURS_DAT = arrayOf("on ikiye", "bire", "ikiye", "üçe", "dörde", "beşe", "altıya", "yediye", "sekize", "dokuza", "ona", "on bire")
        private val ONES = arrayOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
        private val ROMAN = arrayOf("XII", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI")

        private fun minuteWords(m: Int) = if (m == 15) "çeyrek"
        else listOf(arrayOf("", "on", "yirmi")[m / 10], ONES[m % 10]).filter { it.isNotEmpty() }.joinToString(" ")

        /** "Üçü yirmi geçiyor" gibi Türkçe saat söyleyişi, satır satır. */
        fun words(h24: Int, m: Int): List<String> {
            val h = h24 % 12
            return when {
                m == 0 -> listOf("saat", HOURS[h])
                m == 30 -> listOf(HOURS[h], "buçuk")
                m < 30 -> listOf(HOURS_ACC[h], minuteWords(m), "geçiyor")
                else -> listOf(HOURS_DAT[(h + 1) % 12], minuteWords(60 - m), "var")
            }
        }
    }

    var style = style.coerceIn(0, STYLES.lastIndex)
        set(v) { field = v.coerceIn(0, STYLES.lastIndex); requestLayout(); invalidate() }

    /** Akrep/yelkovan dışındaki vurgu rengi (saniye ibresi, dakika vb.). */
    var accent = Palette.accent
        set(v) { field = v; invalidate() }

    private val tr = Locale("tr", "TR")
    private val cal = Calendar.getInstance()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE); strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val path = Path()
    private val thin = Typeface.create("sans-serif-thin", Typeface.NORMAL)
    private val light = Typeface.create("sans-serif-light", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val black = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    private val shadow = 0x99000000.toInt()

    private val tick = Runnable { invalidate(); schedule() }

    private fun schedule() {
        removeCallbacks(tick)
        if (!isAttachedToWindow) return
        val now = System.currentTimeMillis()
        val period = if (STYLES[style].seconds) 1000L else 60_000L
        postDelayed(tick, period - now % period + 20)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = sizeFor(style, 1, 1f, resources.displayMetrics.density)
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        cal.timeInMillis = System.currentTimeMillis()
        val st = STYLES[style]
        // Kutuya oran korunarak ortalanmış çizim alanı
        var w = width.toFloat()
        var h = w / st.aspect
        if (h > height) { h = height.toFloat(); w = h * st.aspect }
        val x0 = (width - w) / 2f
        val y0 = (height - h) / 2f
        canvas.save()
        canvas.translate(x0, y0)
        when (style) {
            0 -> drawThin(canvas, w, h)
            1 -> drawBold(canvas, w, h)
            2 -> drawStacked(canvas, w, h)
            3 -> drawLcd(canvas, w, h)
            4 -> drawFlip(canvas, w, h)
            5 -> drawNeon(canvas, w, h)
            6 -> drawWords(canvas, w, h)
            7 -> drawClassic(canvas, w, h)
            8 -> drawMinimal(canvas, w, h)
            9 -> drawRoman(canvas, w, h)
            10 -> drawSport(canvas, w, h)
            11 -> drawRings(canvas, w, h)
        }
        canvas.restore()
    }

    // ---- Yardımcılar ----

    private val hour get() = cal.get(Calendar.HOUR_OF_DAY)
    private val minute get() = cal.get(Calendar.MINUTE)
    private val second get() = cal.get(Calendar.SECOND)
    private fun two(v: Int) = v.toString().padStart(2, '0')
    private fun date(pattern: String) = SimpleDateFormat(pattern, tr).format(cal.time)

    private fun txt(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface,
                    align: Paint.Align = Paint.Align.CENTER, glow: Float = size * 0.08f, glowColor: Int = shadow, spacing: Float = 0f) {
        text.textSize = size
        text.typeface = tf
        text.color = color
        text.textAlign = align
        text.letterSpacing = spacing
        text.setShadowLayer(glow, 0f, if (glowColor == shadow) size * 0.02f else 0f, glowColor)
        c.drawText(s, x, y, text)
    }

    /** Metni verilen genişliğe sığacak boyuta küçültür. */
    private fun fit(s: String, size: Float, maxW: Float, tf: Typeface, spacing: Float = 0f): Float {
        text.textSize = size; text.typeface = tf; text.letterSpacing = spacing
        val mw = text.measureText(s)
        return if (mw > maxW) size * maxW / mw else size
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, angleDeg: Float, len: Float, back: Float, width: Float, color: Int) {
        val a = Math.toRadians(angleDeg.toDouble() - 90)
        line.strokeWidth = width
        line.color = color
        line.setShadowLayer(width * 1.2f, 0f, width * 0.4f, shadow)
        c.drawLine(cx - cos(a).toFloat() * back, cy - sin(a).toFloat() * back,
            cx + cos(a).toFloat() * len, cy + sin(a).toFloat() * len, line)
        line.clearShadowLayer()
    }

    private val hourAngle get() = (hour % 12 + minute / 60f) * 30f
    private val minuteAngle get() = (minute + second / 60f) * 6f
    private val secondAngle get() = second * 6f

    private fun polar(cx: Float, cy: Float, r: Float, deg: Float): Pair<Float, Float> {
        val a = Math.toRadians(deg.toDouble() - 90)
        return (cx + cos(a).toFloat() * r) to (cy + sin(a).toFloat() * r)
    }

    private fun face(c: Canvas, cx: Float, cy: Float, r: Float, color: Int = 0x66101320) {
        fill.shader = LinearGradient(0f, cy - r, 0f, cy + r, Palette.withAlpha(color, 0x99), color, Shader.TileMode.CLAMP)
        fill.setShadowLayer(r * 0.08f, 0f, r * 0.03f, 0x66000000)
        c.drawCircle(cx, cy, r, fill)
        fill.clearShadowLayer()
        fill.shader = null
    }

    // ---- Dijital ----

    private fun drawThin(c: Canvas, w: Float, h: Float) {
        val t = "${two(hour)}:${two(minute)}"
        val size = fit(t, h * 0.7f, w * 0.96f, thin, 0.02f)
        txt(c, t, w / 2, h * 0.66f, size, Palette.TEXT, thin, spacing = 0.02f, glow = size * 0.1f)
        txt(c, date("d MMMM EEEE"), w / 2, h * 0.92f, h * 0.15f, Palette.TEXT_DIM, light)
    }

    private fun drawBold(c: Canvas, w: Float, h: Float) {
        txt(c, date("EEEE  d MMMM").uppercase(tr), w / 2, h * 0.2f, h * 0.12f, accent, medium, spacing = 0.25f)
        val hh = two(hour)
        val mm = two(minute)
        val size = fit("$hh:$mm", h * 0.72f, w * 0.95f, black)
        text.textSize = size; text.typeface = black; text.letterSpacing = 0f
        val wh = text.measureText(hh)
        val wc = text.measureText(":")
        val wm = text.measureText(mm)
        var x = (w - wh - wc - wm) / 2
        val y = h * 0.88f
        txt(c, hh, x, y, size, Palette.TEXT, black, Paint.Align.LEFT); x += wh
        txt(c, ":", x, y - size * 0.05f, size, Palette.withAlpha(accent, if (second % 2 == 0) 0xFF else 0x88), black, Paint.Align.LEFT); x += wc
        txt(c, mm, x, y, size, Palette.withAlpha(Palette.TEXT, 0xCC), black, Paint.Align.LEFT)
    }

    private fun drawStacked(c: Canvas, w: Float, h: Float) {
        val size = fit("00", h * 0.46f, w * 0.96f, black)
        txt(c, two(hour), w / 2, h * 0.42f, size, Palette.TEXT, black, spacing = -0.02f)
        txt(c, two(minute), w / 2, h * 0.82f, size, accent, black, spacing = -0.02f)
        txt(c, date("EEE d MMM").uppercase(tr), w / 2, h * 0.97f, h * 0.06f, Palette.TEXT_DIM, medium, spacing = 0.2f)
    }

    private fun drawLcd(c: Canvas, w: Float, h: Float) {
        rect.set(0f, 0f, w, h * 0.78f)
        fill.color = 0x55000000
        c.drawRoundRect(rect, h * 0.12f, h * 0.12f, fill)
        line.strokeWidth = h * 0.012f
        line.color = Palette.withAlpha(accent, 0x55)
        c.drawRoundRect(rect, h * 0.12f, h * 0.12f, line)
        val digits = "${two(hour)}${two(minute)}"
        val dh = h * 0.56f
        val dw = dh * 0.52f
        val gap = dw * 0.28f
        val colon = dw * 0.45f
        var x = (w - (4 * dw + 2 * gap + colon + gap)) / 2
        val y = h * 0.11f
        for (i in 0..3) {
            Segments.draw(c, fill, '8', x, y, dw, dh, Palette.withAlpha(accent, 0x16), 0f)
            Segments.draw(c, fill, digits[i], x, y, dw, dh, accent, dh * 0.08f)
            x += dw + gap
            if (i == 1) {
                fill.color = Palette.withAlpha(accent, if (second % 2 == 0) 0xFF else 0x26)
                c.drawCircle(x + colon / 2 - gap / 2, y + dh * 0.32f, dw * 0.08f, fill)
                c.drawCircle(x + colon / 2 - gap / 2, y + dh * 0.68f, dw * 0.08f, fill)
                x += colon
            }
        }
        txt(c, date("EEE dd.MM.yyyy").uppercase(tr), w / 2, h * 0.97f, h * 0.12f, Palette.TEXT_DIM, Typeface.MONOSPACE, spacing = 0.15f)
    }

    private fun drawFlip(c: Canvas, w: Float, h: Float) {
        val cardH = h * 0.8f
        val cardW = cardH * 1.18f
        val gap = w * 0.05f
        var x = (w - 2 * cardW - gap) / 2
        for (part in listOf(two(hour), two(minute))) {
            rect.set(x, 0f, x + cardW, cardH)
            fill.shader = LinearGradient(0f, 0f, 0f, cardH, intArrayOf(0xFF2C2F3A.toInt(), 0xFF1E2029.toInt(), 0xFF15171E.toInt(), 0xFF22242D.toInt()),
                floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.CLAMP)
            fill.setShadowLayer(cardH * 0.06f, 0f, cardH * 0.03f, 0x99000000.toInt())
            c.drawRoundRect(rect, cardH * 0.1f, cardH * 0.1f, fill)
            fill.clearShadowLayer(); fill.shader = null
            txt(c, part, x + cardW / 2, cardH * 0.79f, cardH * 0.72f, 0xFFF2F2F2.toInt(), condensed, glow = 0f)
            // Ortadaki menteşe çizgisi
            fill.color = 0xCC000000.toInt()
            c.drawRect(x, cardH / 2 - cardH * 0.008f, x + cardW, cardH / 2 + cardH * 0.008f, fill)
            fill.color = 0xFF0C0D11.toInt()
            c.drawRoundRect(x - cardW * 0.015f, cardH * 0.44f, x + cardW * 0.025f, cardH * 0.56f, 4f, 4f, fill)
            c.drawRoundRect(x + cardW * 0.975f, cardH * 0.44f, x + cardW * 1.015f, cardH * 0.56f, 4f, 4f, fill)
            x += cardW + gap
        }
        txt(c, date("d MMMM EEEE"), w / 2, h * 0.98f, h * 0.12f, Palette.TEXT_DIM, light)
    }

    private fun drawNeon(c: Canvas, w: Float, h: Float) {
        val t = "${two(hour)}:${two(minute)}"
        val size = fit(t, h * 0.7f, w * 0.94f, thin)
        text.style = Paint.Style.STROKE
        text.strokeWidth = size * 0.022f
        txt(c, t, w / 2, h * 0.68f, size, accent, light, glow = size * 0.12f, glowColor = accent)
        text.style = Paint.Style.FILL
        txt(c, t, w / 2, h * 0.68f, size, Palette.withAlpha(Palette.TEXT, 0x30), light, glow = 0f)
        txt(c, date("d MMMM EEEE").uppercase(tr), w / 2, h * 0.93f, h * 0.11f, Palette.withAlpha(accent, 0xCC), light,
            glow = h * 0.04f, glowColor = accent, spacing = 0.2f)
    }

    private fun drawWords(c: Canvas, w: Float, h: Float) {
        val lines = words(hour, minute).map { it.uppercase(tr) }
        val lineH = h / (lines.size + 0.6f)
        var size = lineH * 0.92f
        lines.forEach { size = min(size, fit(it, size, w * 0.96f, black)) }
        lines.forEachIndexed { i, s ->
            val color = when (i) { 0 -> Palette.TEXT; 1 -> if (lines.size == 3) accent else Palette.TEXT_DIM; else -> Palette.TEXT_DIM }
            txt(c, s, w * 0.02f, lineH * (i + 1), size, color, black, Paint.Align.LEFT)
        }
        txt(c, "${two(hour)}:${two(minute)}  ·  ${date("d MMMM EEEE")}", w * 0.02f, h * 0.97f, h * 0.075f,
            Palette.TEXT_FAINT, medium, Paint.Align.LEFT)
    }

    // ---- Yuvarlak (analog) ----

    private fun drawClassic(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.48f
        face(c, cx, cy, r)
        line.strokeWidth = r * 0.015f
        line.color = 0x55FFFFFF
        c.drawCircle(cx, cy, r, line)
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val (x1, y1) = polar(cx, cy, r * 0.93f, i * 6f)
            val (x2, y2) = polar(cx, cy, r * if (major) 0.82f else 0.88f, i * 6f)
            line.strokeWidth = r * if (major) 0.025f else 0.01f
            line.color = if (major) Palette.TEXT else 0x77FFFFFF
            c.drawLine(x1, y1, x2, y2, line)
        }
        for (i in 1..12) {
            val (x, y) = polar(cx, cy, r * 0.68f, i * 30f)
            txt(c, i.toString(), x, y + r * 0.06f, r * 0.17f, Palette.TEXT, light, glow = 0f)
        }
        txt(c, date("d MMM").uppercase(tr), cx, cy + r * 0.38f, r * 0.09f, Palette.TEXT_DIM, medium, glow = 0f, spacing = 0.15f)
        hand(c, cx, cy, hourAngle, r * 0.48f, r * 0.08f, r * 0.055f, Palette.TEXT)
        hand(c, cx, cy, minuteAngle, r * 0.75f, r * 0.1f, r * 0.035f, Palette.TEXT)
        hand(c, cx, cy, secondAngle, r * 0.84f, r * 0.18f, r * 0.012f, accent)
        fill.color = accent
        c.drawCircle(cx, cy, r * 0.04f, fill)
    }

    private fun drawMinimal(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.47f
        for (i in 0 until 12) {
            val (x, y) = polar(cx, cy, r * 0.92f, i * 30f)
            fill.color = if (i % 3 == 0) Palette.TEXT else 0x88FFFFFF.toInt()
            fill.setShadowLayer(r * 0.03f, 0f, 0f, shadow)
            c.drawCircle(x, y, r * if (i % 3 == 0) 0.035f else 0.02f, fill)
        }
        fill.clearShadowLayer()
        hand(c, cx, cy, hourAngle, r * 0.5f, 0f, r * 0.07f, Palette.TEXT)
        hand(c, cx, cy, minuteAngle, r * 0.8f, 0f, r * 0.045f, accent)
        fill.color = 0xFF101320.toInt()
        c.drawCircle(cx, cy, r * 0.05f, fill)
        line.strokeWidth = r * 0.02f; line.color = Palette.TEXT
        c.drawCircle(cx, cy, r * 0.05f, line)
    }

    private fun drawRoman(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.48f
        val gold = 0xFFE6C88E.toInt()
        face(c, cx, cy, r, 0x99140F0A.toInt())
        line.color = gold
        line.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r * 0.97f, line)
        line.strokeWidth = r * 0.008f
        c.drawCircle(cx, cy, r * 0.9f, line)
        c.drawCircle(cx, cy, r * 0.62f, line)
        for (i in 0 until 60) {
            val (x1, y1) = polar(cx, cy, r * 0.9f, i * 6f)
            val (x2, y2) = polar(cx, cy, r * 0.86f, i * 6f)
            line.strokeWidth = r * if (i % 5 == 0) 0.02f else 0.008f
            c.drawLine(x1, y1, x2, y2, line)
        }
        for (i in 0 until 12) {
            val (x, y) = polar(cx, cy, r * 0.74f, i * 30f)
            txt(c, ROMAN[i], x, y + r * 0.05f, r * 0.14f, gold, serif, glow = 0f)
        }
        hand(c, cx, cy, hourAngle, r * 0.45f, r * 0.06f, r * 0.045f, gold)
        hand(c, cx, cy, minuteAngle, r * 0.72f, r * 0.08f, r * 0.028f, gold)
        hand(c, cx, cy, secondAngle, r * 0.8f, r * 0.15f, r * 0.01f, 0xFFD9534F.toInt())
        fill.color = gold
        c.drawCircle(cx, cy, r * 0.045f, fill)
    }

    private fun drawSport(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.48f
        face(c, cx, cy, r, 0xAA0B0D14.toInt())
        // Dış bezel: koyu halka + vurgu renginde ilk çeyrek
        line.strokeWidth = r * 0.1f
        line.color = 0xFF1C1F2A.toInt()
        c.drawCircle(cx, cy, r * 0.94f, line)
        rect.set(cx - r * 0.94f, cy - r * 0.94f, cx + r * 0.94f, cy + r * 0.94f)
        line.color = Palette.withAlpha(accent, 0xCC)
        line.strokeCap = Paint.Cap.BUTT
        c.drawArc(rect, -90f, 90f, false, line)
        line.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 12) {
            val (x, y) = polar(cx, cy, r * 0.94f, i * 30f)
            txt(c, (i * 5).toString().let { if (i == 0) "60" else it }, x, y + r * 0.025f, r * 0.07f,
                if (i < 3) 0xFF0B0D14.toInt() else Palette.TEXT_DIM, medium, glow = 0f)
        }
        for (i in 0 until 60) {
            val (x1, y1) = polar(cx, cy, r * 0.84f, i * 6f)
            val (x2, y2) = polar(cx, cy, r * if (i % 5 == 0) 0.74f else 0.8f, i * 6f)
            line.strokeWidth = r * if (i % 5 == 0) 0.03f else 0.01f
            line.color = if (i % 5 == 0) Palette.TEXT else 0x66FFFFFF
            c.drawLine(x1, y1, x2, y2, line)
        }
        for (n in listOf(12, 6, 9)) {
            val (x, y) = polar(cx, cy, r * 0.56f, n * 30f)
            txt(c, n.toString(), x, y + r * 0.08f, r * 0.22f, Palette.TEXT, condensed, glow = 0f)
        }
        // Tarih penceresi (saat 3 yönünde)
        rect.set(cx + r * 0.42f, cy - r * 0.1f, cx + r * 0.68f, cy + r * 0.1f)
        fill.color = 0xFFF2F2F2.toInt()
        c.drawRoundRect(rect, r * 0.03f, r * 0.03f, fill)
        txt(c, cal.get(Calendar.DAY_OF_MONTH).toString(), rect.centerX(), cy + r * 0.055f, r * 0.15f, 0xFF111111.toInt(), condensed, glow = 0f)
        hand(c, cx, cy, hourAngle, r * 0.46f, r * 0.08f, r * 0.075f, Palette.TEXT)
        hand(c, cx, cy, minuteAngle, r * 0.72f, r * 0.1f, r * 0.05f, Palette.TEXT)
        hand(c, cx, cy, secondAngle, r * 0.82f, r * 0.22f, r * 0.016f, accent)
        fill.color = accent
        c.drawCircle(cx, cy, r * 0.055f, fill)
    }

    private fun drawRings(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2; val r = min(w, h) * 0.47f
        val sw = r * 0.1f
        val rings = listOf(
            Triple(r - sw / 2, (hour % 12 + minute / 60f) / 12f, accent),
            Triple(r - sw * 1.75f, (minute + second / 60f) / 60f, Palette.companion(accent)),
            Triple(r - sw * 3f, second / 60f, Palette.TEXT),
        )
        line.strokeWidth = sw
        for ((rr, p, color) in rings) {
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.shader = null
            line.color = Palette.withAlpha(color, 0x22)
            c.drawCircle(cx, cy, rr, line)
            line.color = color
            line.setShadowLayer(sw * 0.5f, 0f, 0f, Palette.withAlpha(color, 0x88))
            c.drawArc(rect, -90f, 360f * p.coerceAtLeast(0.004f), false, line)
            line.clearShadowLayer()
        }
        val t = "${two(hour)}:${two(minute)}"
        val size = fit(t, r * 0.42f, r * 0.95f, light)
        txt(c, t, cx, cy + size * 0.32f, size, Palette.TEXT, light)
        txt(c, date("d MMM EEE").uppercase(tr), cx, cy + size * 0.32f + r * 0.18f, r * 0.08f, Palette.TEXT_DIM, medium, spacing = 0.15f)
    }
}

/** Yedi parçalı (LCD/hesap makinesi tipi) rakam çizimi. Saat ve radyo göstergeleri kullanır. */
object Segments {
    // a b c d e f g sırası (üst, sağ üst, sağ alt, alt, sol alt, sol üst, orta)
    private val MAP = mapOf(
        '0' to 0b1111110, '1' to 0b0110000, '2' to 0b1101101, '3' to 0b1111001, '4' to 0b0110011,
        '5' to 0b1011011, '6' to 0b1011111, '7' to 0b1110000, '8' to 0b1111111, '9' to 0b1111011, '-' to 0b0000001,
    )
    private val r = RectF()

    fun draw(c: Canvas, p: Paint, ch: Char, x: Float, y: Float, w: Float, h: Float, color: Int, glow: Float) {
        val bits = MAP[ch] ?: return
        val t = w * 0.16f
        val g = t * 0.18f
        val half = h / 2
        p.shader = null
        p.color = color
        if (glow > 0f) p.setShadowLayer(glow, 0f, 0f, color) else p.clearShadowLayer()
        fun on(i: Int) = bits shr (6 - i) and 1 == 1
        fun hSeg(yy: Float) { r.set(x + t * 0.5f + g, yy - t / 2, x + w - t * 0.5f - g, yy + t / 2); c.drawRoundRect(r, t / 2, t / 2, p) }
        fun vSeg(xx: Float, y1: Float, y2: Float) { r.set(xx - t / 2, y1 + g, xx + t / 2, y2 - g); c.drawRoundRect(r, t / 2, t / 2, p) }
        val l = x + t / 2; val rr = x + w - t / 2
        val top = y + t / 2; val mid = y + half; val bot = y + h - t / 2
        // Hafif italik görünüm için alt kısım biraz sola kayar
        c.save()
        c.skew(-0.08f, 0f)
        c.translate(y * 0.08f + h * 0.04f, 0f)
        if (on(0)) hSeg(top)
        if (on(1)) vSeg(rr, top, mid)
        if (on(2)) vSeg(rr, mid, bot)
        if (on(3)) hSeg(bot)
        if (on(4)) vSeg(l, mid, bot)
        if (on(5)) vSeg(l, top, mid)
        if (on(6)) hSeg(mid)
        c.restore()
        p.clearShadowLayer()
    }
}
