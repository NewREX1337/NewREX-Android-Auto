// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * TV seçim ekranındaki kısayolların kare logoları. Hepsi aynı boyutta, ortalı ve yuvarlak köşeli çizilir
 * (sitelerden gelen resimler farklı oranlarda olduğu için kutucukta kırpılıyordu). [parts] yazının parçaları
 * ve renkleri, ör. "tv" beyaz + "+" sarı.
 */
class BrandArt(
    private val top: Int,
    private val bottom: Int,
    private val parts: List<Pair<String, Int>>,
    private val density: Float,
    private val typeface: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL),
    private val textScale: Float = 0.5f,
) : Drawable() {
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = this@BrandArt.typeface }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        rect.set(b.exactCenterX() - side / 2, b.exactCenterY() - side / 2, b.exactCenterX() + side / 2, b.exactCenterY() + side / 2)
        bg.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 12 * density, 12 * density, bg)

        // Yazı karenin içine sığacak boyutta, tam ortada
        text.textSize = side * textScale
        var width = parts.sumOf { text.measureText(it.first).toDouble() }.toFloat()
        val max = side * 0.82f
        if (width > max) { text.textSize *= max / width; width = max }
        var x = rect.centerX() - width / 2
        val y = rect.centerY() - (text.descent() + text.ascent()) / 2
        for ((s, c) in parts) {
            text.color = c
            canvas.drawText(s, x, y, text)
            x += text.measureText(s)
        }
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { bg.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private val condensed = Typeface.create("sans-serif-condensed", Typeface.BOLD)

        fun tvChannels(d: Float) = BrandArt(0xFF0891B2.toInt(), 0xFF0E4F63.toInt(), listOf("TV" to Color.WHITE), d)
        fun iptv(d: Float) = BrandArt(0xFF7C5CFF.toInt(), 0xFF3B2A86.toInt(), listOf("IPTV" to Color.WHITE), d, textScale = 0.36f)
        fun tvPlus(d: Float) = BrandArt(0xFF1A1A1A.toInt(), 0xFF000000.toInt(),
            listOf("tv" to Color.WHITE, "+" to 0xFFFFD60A.toInt()), d)
        fun netflix(d: Float) = BrandArt(0xFF1A1A1A.toInt(), 0xFF000000.toInt(),
            listOf("N" to 0xFFE50914.toInt()), d, typeface = condensed, textScale = 0.72f)
    }
}
