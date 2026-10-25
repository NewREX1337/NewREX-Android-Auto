// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.view.MotionEvent
import android.view.View

/**
 * Renk seçici: üstte doygunluk/parlaklık karesi, altta renk çubuğu. Dokunarak ya da sürükleyerek seçilir.
 * [maxValue] 1'den küçükse en parlak renk sınırlanır (arka plan renginde yazılar okunsun diye).
 */
class ColorPickerView(context: Context, initial: Int, private val maxValue: Float = 1f) : View(context) {
    private val hsv = FloatArray(3).also {
        Color.colorToHSV(initial, it)
        it[2] = it[2].coerceAtMost(maxValue)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sv = RectF()
    private val hue = RectF()
    private var target = 0

    /** Seçim değiştikçe (sürüklerken de). */
    var onChange: ((Int) -> Unit)? = null
    /** Parmak kalkınca: seçimi uygula. */
    var onCommit: ((Int) -> Unit)? = null

    val color: Int get() = Color.HSVToColor(hsv)

    fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceAtMost(maxValue)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val bar = context.dpf(22)
        val gap = context.dpf(16)
        val pad = context.dpf(12)
        sv.set(pad, pad, w - pad, h - bar - gap - pad)
        hue.set(pad, h - bar - pad, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        val r = context.dpf(12)
        // Kare: seçili ton → sağa doğru doygunluk artar, aşağı doğru kararır
        fill.shader = null
        fill.color = Color.HSVToColor(floatArrayOf(hsv[0], 1f, maxValue))
        canvas.drawRoundRect(sv, r, r, fill)
        fill.shader = LinearGradient(sv.left, 0f, sv.right, 0f, Color.HSVToColor(floatArrayOf(0f, 0f, maxValue)), 0, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, r, r, fill)
        fill.shader = LinearGradient(0f, sv.top, 0f, sv.bottom, 0, Color.BLACK, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(sv, r, r, fill)

        // Renk çubuğu
        fill.shader = LinearGradient(hue.left, 0f, hue.right, 0f,
            IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hue, hue.height() / 2, hue.height() / 2, fill)
        fill.shader = null

        // Seçim halkaları
        val sx = sv.left + hsv[1] * sv.width()
        val sy = sv.top + (1f - hsv[2] / maxValue) * sv.height()
        drawKnob(canvas, sx, sy, color)
        drawKnob(canvas, hue.left + hsv[0] / 360f * hue.width(), hue.centerY(), Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f)))
    }

    private fun drawKnob(canvas: Canvas, x: Float, y: Float, c: Int) {
        val rad = context.dpf(11)
        fill.color = c
        canvas.drawCircle(x, y, rad, fill)
        ring.strokeWidth = context.dpf(3)
        ring.color = Color.WHITE
        canvas.drawCircle(x, y, rad, ring)
        ring.strokeWidth = context.dpf(1)
        ring.color = 0x66000000
        canvas.drawCircle(x, y, rad + context.dpf(2), ring)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Kaydırma (sayfa, liste) seçimi bölmesin
                parent?.requestDisallowInterceptTouchEvent(true)
                target = if (ev.y >= hue.top - context.dpf(10)) 2 else 1
                pick(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> pick(ev.x, ev.y)
            MotionEvent.ACTION_UP -> { pick(ev.x, ev.y); onCommit?.invoke(color) }
            MotionEvent.ACTION_CANCEL -> onCommit?.invoke(color)
        }
        return true
    }

    private fun pick(x: Float, y: Float) {
        if (target == 2) {
            hsv[0] = ((x - hue.left) / hue.width()).coerceIn(0f, 1f) * 359.9f
        } else {
            hsv[1] = ((x - sv.left) / sv.width()).coerceIn(0f, 1f)
            hsv[2] = (1f - (y - sv.top) / sv.height()).coerceIn(0f, 1f) * maxValue
        }
        invalidate()
        onChange?.invoke(color)
    }

    companion object {
        fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

        fun parseHex(s: String): Int? {
            val t = s.trim().removePrefix("#")
            if (t.length != 6 || t.any { it.lowercaseChar() !in "0123456789abcdef" }) return null
            return (0xFF000000 or t.toLong(16)).toInt()
        }

        /** "Özel renk" düğmesi için gökkuşağı daire. */
        fun rainbowDot(): Drawable = ShapeDrawable(OvalShape()).apply {
            paint.shader = null
            shaderFactory = object : ShapeDrawable.ShaderFactory() {
                override fun resize(width: Int, height: Int): Shader = SweepGradient(width / 2f, height / 2f,
                    IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 1f)) }, null)
            }
        }
    }
}
