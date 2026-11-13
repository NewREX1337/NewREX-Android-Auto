// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * Trafik levhası görünümünde hız sınırı: beyaz zemin, kırmızı halka, ortada sayı. [limit] null ise "kapalı"
 * levhası (gri halka, çapraz çizgi). Seçiliyse etrafında tema renginde ışık halkası çizilir.
 */
class SpeedSign(private val limit: Int?, private val selected: Boolean) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val font = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY()
        val r = min(b.width(), b.height()) / 2f * 0.84f
        if (selected) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = r * 0.14f
            p.color = Palette.withAlpha(Palette.accent, 0x99)
            p.maskFilter = BlurMaskFilter(r * 0.12f, BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(cx, cy, r * 1.1f, p)
            p.maskFilter = null
            p.color = Palette.accent
            p.strokeWidth = r * 0.06f
            c.drawCircle(cx, cy, r * 1.1f, p)
        }
        p.style = Paint.Style.FILL
        p.color = if (limit != null) 0xFFF5F5F5.toInt() else 0xFF2A2E38.toInt()
        c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.18f
        p.color = if (limit != null) 0xFFE0262F.toInt() else 0xFF6B7280.toInt()
        c.drawCircle(cx, cy, r - p.strokeWidth / 2, p)
        if (limit != null) {
            p.style = Paint.Style.FILL
            p.color = 0xFF111111.toInt()
            p.typeface = font
            p.textAlign = Paint.Align.CENTER
            p.textSize = r * (if (limit >= 100) 0.78f else 0.92f)
            c.drawText("$limit", cx, cy + p.textSize * 0.35f, p)
        } else {
            p.strokeWidth = r * 0.12f
            p.color = 0xFF9CA3AF.toInt()
            val d = r * 0.5f
            c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
