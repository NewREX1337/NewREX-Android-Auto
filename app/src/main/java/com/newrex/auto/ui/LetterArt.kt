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

/** Logosu olmayan istasyon için baş harfli, istasyona özgü renkte kare (ör. "Joy Türk" → "JT"). */
class LetterArt(title: String, private val density: Float) : Drawable() {
    private val letters: String
    private val color: Int
    private val dark: Int

    init {
        // "Radyo", "FM" gibi her istasyonda geçen kelimeler harf olmasın
        val skip = setOf("radyo", "radio", "fm", "the", "turkey", "türkiye")
        val words = title.split(Regex("[\\s\\-_.]+")).filter { it.isNotEmpty() && it.lowercase() !in skip }
            .ifEmpty { title.split(Regex("\\s+")).filter { it.isNotEmpty() } }
        letters = words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "♪" }
        val hue = ((title.lowercase().hashCode() and 0x7FFFFFFF) % 360).toFloat()
        color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f))
        dark = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.45f))
    }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val side = minOf(b.width(), b.height()).toFloat()
        rect.set(b.exactCenterX() - side / 2, b.exactCenterY() - side / 2, b.exactCenterX() + side / 2, b.exactCenterY() + side / 2)
        bg.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, color, dark, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, bg)
        text.textSize = side * (if (letters.length > 1) 0.38f else 0.48f)
        val y = rect.centerY() - (text.descent() + text.ascent()) / 2
        canvas.drawText(letters, rect.centerX(), y, text)
    }

    override fun setAlpha(alpha: Int) { bg.alpha = alpha; text.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { bg.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
