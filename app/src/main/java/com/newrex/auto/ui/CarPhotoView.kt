// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import com.newrex.auto.data.CarImage

/** Kullanıcının yüklediği araç görseli: oranı bozulmadan sığdırılır, altında tema renginde ışık halkası. */
class CarPhotoView(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dst = RectF()
    private var glowKey = 0

    override fun onDraw(c: Canvas) {
        val bmp = CarImage.bitmap(context) ?: return
        val w = width.toFloat(); val h = height.toFloat()
        var fw = w * 0.96f
        var fh = fw * bmp.height / bmp.width
        if (fh > h) { fh = h; fw = fh * bmp.width / bmp.height }
        dst.set((w - fw) / 2, h - fh, (w + fw) / 2, h)
        drawGlow(c, dst)
        c.drawBitmap(bmp, null, dst, bmpPaint)
    }

    private fun drawGlow(c: Canvas, r: RectF) {
        val accent = gaugeAccent()
        val cx = r.centerX()
        val cy = r.bottom - r.height() * 0.08f
        val rx = r.width() * 0.4f
        val ry = rx * 0.12f
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
        ringPaint.strokeWidth = 1.5f * d
        ringPaint.color = Palette.withAlpha(accent, 150)
        c.drawOval(cx - rx * 0.78f, cy - ry * 0.55f, cx + rx * 0.78f, cy + ry * 0.55f, ringPaint)
        ringPaint.color = Palette.withAlpha(accent, 60)
        c.drawOval(cx - rx * 0.95f, cy - ry * 0.75f, cx + rx * 0.95f, cy + ry * 0.75f, ringPaint)
    }
}
