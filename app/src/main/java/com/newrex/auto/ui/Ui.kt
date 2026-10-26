// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
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
    /** Kartların zemini: koyu, yarı saydam cam. Arkada açık renkli bir şey olsa da yazılar okunur. */
    val SURFACE = 0xA6161A2A.toInt()
    const val SURFACE_STRONG = 0x2EFFFFFF
    val TEXT = Color.WHITE
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val TEXT_FAINT = 0x73FFFFFF
    val GREEN = 0xFF22C55E.toInt()
    val ORANGE = 0xFFFF8A00.toInt()
    val RED = 0xFFE53950.toInt()

    var accent = accents[0]

    /** En çok kullanılan renklerin yanında bir ek renk (camgöbeği); geri kalanı "Özel renk" ile seçilir. */
    val extraAccents = intArrayOf(0xFF00BCD4.toInt())

    /** Hazır koyu arka planlar (0 = otomatik). */
    val backgrounds = intArrayOf(
        0, 0xFF000000.toInt(), 0xFF0B0E1A.toInt(), 0xFF0D1A33.toInt(), 0xFF1A1033.toInt(), 0xFF0D2B1E.toInt(),
        0xFF1C1F24.toInt(),
    )

    /** Kayıtlı ayarlardan tema rengini yükler. */
    fun load() {
        val i = com.newrex.auto.data.Prefs.accentIndex
        accent = if (i in accents.indices) accents[i] else com.newrex.auto.data.Prefs.customAccent
    }

    /** Arka plan rengi çok açık seçilirse yazılar okunmaz; parlaklığı sınırlanır. */
    const val MAX_BG_VALUE = 0.42f

    fun safeBackground(c: Int): Int {
        if (c == 0) return 0
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[2] = hsv[2].coerceAtMost(MAX_BG_VALUE)
        return Color.HSVToColor(hsv)
    }

    /** Tema renginin yanına yakışan ikinci renk (arka plandaki ışık şeritleri için). */
    fun companion(c: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hsv[0] = (hsv[0] + 40f) % 360f
        return Color.HSVToColor(hsv)
    }

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

/** Dokununca tema renginde parlayan arka plan. */
fun pressable(content: Drawable, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(Palette.withAlpha(Palette.accent, 0x38)), content, rounded(Color.WHITE, radius))

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun flp(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun <T : ViewGroup.MarginLayoutParams> T.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0): T =
    apply { setMargins(l, t, r, b) }

/**
 * Dokunulunca tema renginde kısa bir neon parlaması gösterir. Sayfa değiştiren dokunmalarda işlem
 * parlamanın görülebilmesi için çok kısa gecikir; klavye gibi hızlı kullanılanlarda [immediate] = true.
 */
fun View.onTap(action: () -> Unit) = onTap(false, action)

fun View.onTap(immediate: Boolean, action: () -> Unit) = setOnClickListener { v ->
    if (com.newrex.auto.Saver.on) { action(); return@setOnClickListener }
    v.neonFlash()
    if (immediate) { action(); return@setOnClickListener }
    if (v.getTag(com.newrex.auto.R.id.neon_pending) == true) return@setOnClickListener
    v.setTag(com.newrex.auto.R.id.neon_pending, true)
    v.postDelayed({ v.setTag(com.newrex.auto.R.id.neon_pending, null); action() }, 110)
}

/**
 * Kenarlardan içeri doğru yumuşakça sönen tema renginde ışık. Arka planın bir katmanı olarak çizilir,
 * yani yazı ve resimler her zaman önünde kalır.
 */
class NeonDrawable(private val radius: Float, private val depth: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var level = 0

    override fun draw(canvas: Canvas) {
        if (level == 0) return
        val accent = Palette.accent
        fill.color = Palette.withAlpha(accent, 0x22 * level / 255)
        rect.set(bounds)
        canvas.drawRoundRect(rect, radius, radius, fill)
        val steps = 7
        val w = depth / steps
        paint.strokeWidth = w + 0.5f
        for (i in 0 until steps) {
            val inset = w * i + w / 2
            rect.set(bounds)
            rect.inset(inset, inset)
            val a = (0xD0 * (1f - i / steps.toFloat()).let { it * it } * level / 255).toInt()
            paint.color = Palette.withAlpha(accent, a)
            val r = (radius - inset).coerceAtLeast(0f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        level = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun getAlpha() = level
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

private fun View.cornerRadius(): Float {
    val bg = background
    val g = (bg as? RippleDrawable)?.getDrawable(0) ?: bg
    return (g as? GradientDrawable)?.cornerRadius?.takeIf { it > 0 } ?: context.dpf(12)
}

/** Görünümün arka planına (bir kez) neon katmanı ekler; arka plan değiştiyse yeniden ekler. */
private fun View.neonLayer(): NeonDrawable {
    val wrapper = getTag(com.newrex.auto.R.id.neon_layer) as? LayerDrawable
    if (wrapper != null && wrapper === background) return wrapper.getDrawable(1) as NeonDrawable
    val neon = NeonDrawable(cornerRadius(), context.dpf(10))
    val layered = LayerDrawable(arrayOf(background ?: ColorDrawable(0), neon))
    background = layered
    setTag(com.newrex.auto.R.id.neon_layer, layered)
    return neon
}

/** Seçili sekme, çalan kanal gibi öğelerde sürekli hafif neon ışıma. */
fun View.neonGlow(on: Boolean, @Suppress("UNUSED_PARAMETER") strengthDp: Int = 8) {
    if (!on && getTag(com.newrex.auto.R.id.neon_layer) == null) return
    val neon = neonLayer()
    setTag(com.newrex.auto.R.id.neon_base, if (on) 150 else 0)
    if ((getTag(com.newrex.auto.R.id.neon_anim) as? Animator)?.isRunning != true) neon.alpha = if (on) 150 else 0
}

/** Dokununca kartın içinde, kenarlardan yayılan kısa bir neon parlaması. */
fun View.neonFlash() {
    val neon = neonLayer()
    (getTag(com.newrex.auto.R.id.neon_anim) as? Animator)?.cancel()
    val base = getTag(com.newrex.auto.R.id.neon_base) as? Int ?: 0
    val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 700
        addUpdateListener { va ->
            val t = va.animatedValue as Float
            // Hızlı yanıp yavaşça sönen parlama
            val f = if (t < 0.15f) t / 0.15f else (1f - (t - 0.15f) / 0.85f).let { it * it }
            neon.alpha = (base + (255 - base) * f).toInt()
            val s = 1f - 0.025f * f
            scaleX = s; scaleY = s
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                neon.alpha = base
                scaleX = 1f; scaleY = 1f
            }
        })
    }
    setTag(com.newrex.auto.R.id.neon_anim, anim)
    anim.start()
}

/** Arka plana tema renginde yumuşak ışık lekeleri ekler. */
fun ambientBackground(context: Context): Drawable {
    val base = gradient(Palette.BG_TOP, Palette.BG_BOTTOM, orientation = GradientDrawable.Orientation.TOP_BOTTOM)
    fun blob(cx: Float, cy: Float, radiusDp: Int, alpha: Int) = GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = context.dpf(radiusDp)
        setGradientCenter(cx, cy)
        colors = intArrayOf(Palette.withAlpha(Palette.accent, alpha), 0)
    }
    return LayerDrawable(arrayOf(base, blob(0.08f, 0.0f, 340, 0x48), blob(1.0f, 1.0f, 300, 0x30)))
}

/** Yazıya tema renginde neon ışıltı. */
fun TextView.neonText(radiusDp: Int = 10) {
    setShadowLayer(context.dpf(radiusDp), 0f, 0f, Palette.withAlpha(Palette.accent, 0xCC))
}

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
    // Açık renkli bir zemine denk gelse de okunabilsin diye hafif gölge
    setShadowLayer(dpf(3), 0f, dpf(1), 0x99000000.toInt())
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
    neonGlow(selected, 6)
    setPadding(dp(16), 0, dp(16), 0)
    layoutParams = LinearLayout.LayoutParams(WRAP, dp(heightDp))
    if (res != null) addView(icon(res, 20), lp(dp(20), dp(20)).margins(r = dp(8)))
    addView(label(text, 15f, bold = selected))
    onTap(onClick)
}

/** Yarı saydam yuvarlatılmış panel. */
fun Context.panel(orientationVertical: Boolean = true, radiusDp: Int = 18) = LinearLayout(this).apply {
    orientation = if (orientationVertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    background = rounded(Palette.SURFACE, dpf(radiusDp), dp(1), 0x1AFFFFFF)
}
