// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.data.CarCatalog
import com.newrex.auto.data.CarFrames
import com.newrex.auto.data.CarPaint
import com.newrex.auto.data.Prefs
import com.newrex.auto.t
import com.newrex.auto.ui.CarSpinView
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.CarVisual
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pill
import kotlinx.coroutines.launch

/**
 * "Aracım": göstergenin ortasındaki araç. Telefondan kendi araç görselini (PNG, JPG...) yükleyebilirsin;
 * yüklemezsen hazır araç (beyaz Toyota C-HR X-Sport) görünür.
 */
class GaragePage(ui: CarUi) : Page(ui) {
    override val title = t("Aracım")

    private lateinit var grades: LinearLayout
    private lateinit var swatches: LinearLayout
    private lateinit var info: TextView
    private lateinit var car: CarSpinView
    private lateinit var preview: FrameLayout
    private lateinit var actions: LinearLayout

    override fun build(): View = with(ctx) {
        CarFrames.load(applicationContext)
        car = CarSpinView(this)
        preview = FrameLayout(this)
        actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        scope.launch { CarFrames.version.collect { car.invalidate(); refreshInfo() } }
        scope.launch { com.newrex.auto.data.CarImage.version.collect { showPreview(); refreshInfo() } }
        info = label("", 14f, Palette.TEXT_DIM)
        grades = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fill()
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(6), dp(20), dp(12))
            addView(preview, lp(MATCH, 0, 1f))
            addView(info.apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(b = dp(8)))
            if (CarVisual.FULL_SPIN) {
                addView(HorizontalScrollView(this@with).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(grades)
                }, lp(WRAP, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(HorizontalScrollView(this@with).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(swatches)
                }, lp(WRAP, WRAP).margins(t = dp(10), b = dp(10)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            }
            addView(actions, lp(MATCH, WRAP))
        }
    }

    private fun custom() = com.newrex.auto.data.CarImage.has(ctx)

    /** Kendi görseli varsa o, yoksa hazır araç; altında yükle / varsayılana dön. */
    private fun showPreview() = with(ctx) {
        preview.removeAllViews()
        (car.parent as? android.view.ViewGroup)?.removeView(car)
        preview.addView(if (custom()) com.newrex.auto.ui.CarPhotoView(this) else car, FrameLayout.LayoutParams(MATCH, MATCH))
        actions.removeAllViews()
        actions.addView(pill(t("Telefondan görsel yükle"), com.newrex.auto.R.drawable.ic_photo, heightDp = 44) {
            if (com.newrex.auto.DriveState.driving.value) { ui.toast(t("Fotoğrafı park halindeyken seçebilirsin.")); return@pill }
            com.newrex.auto.CarImageActivity.start(applicationContext)
            ui.toast(t("Telefonunda görsel seçme ekranı açıldı. Arka planı silip önizleyebilirsin."), long = true)
        }, lp(WRAP, dp(44)))
        if (custom()) actions.addView(pill(t("Hazır araca dön"), com.newrex.auto.R.drawable.ic_close, heightDp = 44) {
            com.newrex.auto.data.CarImage.remove(applicationContext)
        }, lp(WRAP, dp(44)).margins(l = dp(10)))
    }

    private fun refreshInfo() {
        if (custom()) { info.text = t("Kendi araç görselin. En güzel sonuç: arka planı şeffaf PNG."); return }
        val g = CarCatalog.selectedGrade
        val p = CarCatalog.selectedPaint
        val ready = CarFrames.current.value?.ready ?: 0
        val need = if (CarVisual.FULL_SPIN) CarCatalog.FRAMES else 1
        val err = CarFrames.error.value
        info.text = "Toyota C-HR ${g.name}  ·  ${p.name}" + when {
            err != null && ready < need -> "  ·  $err"
            ready < need -> "  ·  " + t("Görsel indiriliyor…") + " %${ready * 100 / need}"
            CarVisual.FULL_SPIN -> "  ·  " + t("Çevirmek için yana kaydır")
            else -> ""
        }
    }

    private fun fill() = with(ctx) {
        val selG = CarCatalog.selectedGrade
        grades.removeAllViews()
        CarCatalog.grades.forEach { g ->
            grades.addView(pill(g.name, selected = g.id == selG.id, heightDp = 44) {
                if (g.id != Prefs.spinGrade) {
                    // Aynı renk yeni pakette varsa o kalsın (tek renk / siyah tavanlı adları eşleşir)
                    val cur = CarCatalog.selectedPaint.name.substringBefore(" /")
                    Prefs.spinGrade = g.id
                    Prefs.spinPaint = (g.paints.firstOrNull { it.name.substringBefore(" /") == cur } ?: g.paints.first()).code
                    changed()
                }
            }, lp(WRAP, dp(44)).margins(r = dp(10)))
        }
        swatches.removeAllViews()
        val selP = CarCatalog.selectedPaint
        selG.paints.forEach { p ->
            swatches.addView(Swatch(this, p, p.code == selP.code).apply {
                onTap { if (p.code != Prefs.spinPaint) { Prefs.spinPaint = p.code; changed() } }
            }, lp(dp(52), dp(52)).margins(r = dp(12)))
        }
        refreshInfo()
    }

    private fun changed() {
        CarFrames.load(ctx.applicationContext)
        fill()
        car.invalidate()
    }

    /** Renk yuvarlağı; siyah tavanlı renklerde üst yarısı siyah. */
    private class Swatch(context: Context, val paint: CarPaint, val selected: Boolean) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var shine: Shader? = null

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            shine = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r = width / 2f - 6 * d
            if (selected) {
                stroke.color = Palette.accent; stroke.strokeWidth = 3 * d
                c.drawCircle(cx, cy, width / 2f - 1.5f * d, stroke)
            }
            fill.shader = null; fill.color = paint.rgb
            c.drawCircle(cx, cy, r, fill)
            if ("/" in paint.name) {
                fill.color = 0xFF111114.toInt()
                c.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, true, fill)
            }
            fill.shader = shine
            c.drawCircle(cx, cy, r, fill)
            stroke.color = 0x40FFFFFF; stroke.strokeWidth = 1.2f * d
            c.drawCircle(cx, cy, r, stroke)
        }
    }
}
