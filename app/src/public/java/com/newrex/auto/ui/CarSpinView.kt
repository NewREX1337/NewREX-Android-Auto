// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.newrex.auto.data.CarCatalog
import com.newrex.auto.data.CarFrames

/** Bu derlemede araç tek bir fotoğraf (PNG) olarak gösterilir. */
object CarVisual {
    const val FULL_SPIN = false
}

/**
 * Seçilen paketteki ve renkteki aracın tek fotoğrafı, altında tema renginde ışık halkası.
 * (Kaydırma çağrıları bu sürümde bir şey yapmaz.)
 */
class CarSpinView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0x99FFFFFF.toInt() }
    private val dst = RectF()
    private var glowKey = 0

    fun turnBy(frames: Float) {}
    fun dragStart() {}
    fun dragBy(dxPx: Float) {}
    fun dragEnd(cancel: Boolean = false) {}

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        var fw = w
        var fh = fw * CarCatalog.H / CarCatalog.W
        if (fh > h) { fh = h; fw = fh * CarCatalog.W / CarCatalog.H }
        dst.set((w - fw) / 2, h - fh, (w + fw) / 2, h)
        drawGlow(c, dst)
        val bmp = CarFrames.current.value?.frames?.firstOrNull { it != null }
        if (bmp != null) c.drawBitmap(bmp, null, dst, bmpPaint)
        else {
            msgPaint.textSize = (h * 0.07f).coerceIn(12f, 28f)
            c.drawText(CarFrames.error.value ?: com.newrex.auto.t("Araç görseli hazırlanıyor…"), w / 2, h * 0.6f, msgPaint)
        }
    }

    private fun drawGlow(c: Canvas, r: RectF) {
        val accent = gaugeAccent()
        val cx = r.centerX()
        val cy = r.bottom - r.height() * 0.11f
        val rx = r.width() * 0.37f
        val ry = r.height() * 0.12f
        val key = java.util.Objects.hash(cx, cy, rx, accent)
        if (glowKey != key || glowPaint.shader == null) {
            glowKey = key
            glowPaint.shader = RadialGradient(cx, cy, rx, intArrayOf(Palette.withAlpha(accent, 150), Palette.withAlpha(accent, 40), 0),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.save()
        c.scale(1f, ry / rx, cx, cy)
        c.drawCircle(cx, cy, rx, glowPaint)
        c.restore()
        ringPaint.strokeWidth = (r.height() * 0.008f).coerceAtLeast(1.5f * d)
        ringPaint.color = Palette.withAlpha(accent, 150)
        c.drawOval(cx - rx * 0.78f, cy - ry * 0.55f, cx + rx * 0.78f, cy + ry * 0.55f, ringPaint)
        ringPaint.color = Palette.withAlpha(accent, 60)
        c.drawOval(cx - rx * 0.95f, cy - ry * 0.75f, cx + rx * 0.95f, cy + ry * 0.75f, ringPaint)
    }
}
