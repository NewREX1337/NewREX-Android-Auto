// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object Palette {
    val accents = intArrayOf(
        0xFF7C5CFF.toInt(), // mor
        0xFF2F80ED.toInt(), // mavi
        0xFFE53950.toInt(), // kırmızı
        0xFF16B98A.toInt(), // yeşil
        0xFFFF8A00.toInt(), // turuncu
        0xFFE040FB.toInt(), // pembe
    )
    val BG_TOP = 0xFF0B0E1A.toInt()
    val BG_BOTTOM = 0xFF17102B.toInt()
    const val SURFACE = 0x1AFFFFFF
    const val SURFACE_STRONG = 0x2EFFFFFF
    val TEXT = Color.WHITE
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val TEXT_FAINT = 0x73FFFFFF
    val GREEN = 0xFF22C55E.toInt()
    val ORANGE = 0xFFFF8A00.toInt()
    val RED = 0xFFE53950.toInt()

    var accent = accents[0]

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, strokeWidth: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
}

fun gradient(
    c1: Int, c2: Int, radius: Float = 0f,
    orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR,
) = GradientDrawable(orientation, intArrayOf(c1, c2)).apply { cornerRadius = radius }

/** Dokununca parlayan arka plan. */
fun pressable(content: Drawable, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), content, rounded(Color.WHITE, radius))

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun <T : ViewGroup.MarginLayoutParams> T.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T =
    apply { setMargins(l, t, r, b) }

fun View.onTap(action: () -> Unit) = setOnClickListener { action() }

fun Context.label(
    text: CharSequence,
    size: Float,
    color: Int = Palette.TEXT,
    bold: Boolean = false,
    lines: Int = 1,
) = TextView(this).apply {
    this.text = text
    // Telefonun yazı boyutu ayarı araç ekranını bozmasın diye sp yerine dp
    setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
    setTextColor(color)
    typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD) else Typeface.SANS_SERIF
    maxLines = lines
    ellipsize = TextUtils.TruncateAt.END
    includeFontPadding = false
}

fun Context.icon(res: Int, sizeDp: Int, tint: Int = Palette.TEXT) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/** Yuvarlak simge butonu. */
fun Context.circleButton(
    res: Int,
    sizeDp: Int = 44,
    bg: Int = Palette.SURFACE_STRONG,
    tint: Int = Palette.TEXT,
    iconDp: Int = (sizeDp * 0.55f).toInt(),
    onClick: () -> Unit,
) = FrameLayout(this).apply {
    val r = dpf(sizeDp / 2f)
    background = pressable(rounded(bg, r), r)
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
    addView(icon(res, iconDp, tint), flp(dp(iconDp), dp(iconDp), Gravity.CENTER))
    onTap(onClick)
}

/** Simgeli, yazılı hap şeklinde buton. */
fun Context.pill(
    text: String,
    res: Int? = null,
    selected: Boolean = false,
    heightDp: Int = 40,
    onClick: () -> Unit,
) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    val r = dpf(heightDp / 2f)
    val bg = if (selected) Palette.accent else Palette.SURFACE_STRONG
    background = pressable(rounded(bg, r), r)
    setPadding(dp(16), 0, dp(16), 0)
    layoutParams = LinearLayout.LayoutParams(WRAP, dp(heightDp))
    if (res != null) addView(icon(res, 20), lp(dp(20), dp(20)).margins(r = dp(8)))
    addView(label(text, 15f, bold = selected))
    onTap(onClick)
}

/** Yarı saydam yuvarlatılmış panel. */
fun Context.panel(orientationVertical: Boolean = true, radiusDp: Int = 18) = LinearLayout(this).apply {
    orientation = if (orientationVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    background = rounded(Palette.SURFACE, dpf(radiusDp))
}
