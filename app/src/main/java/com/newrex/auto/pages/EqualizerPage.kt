// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.R
import com.newrex.auto.PlayerHub
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.rounded

/** 5 bantlı ekolayzır: 60 Hz, 230 Hz, 910 Hz, 3.6 kHz, 14 kHz. */
class EqualizerPage(ui: CarUi) : Page(ui) {
    override val title = t("Ekolayzır")

    private companion object {
        val BANDS = arrayOf("60", "230", "910", "3.6k", "14k")
        const val MIN_LEVEL = -15
        const val MAX_LEVEL = 15
        val PRESETS = mapOf(
            t("Düz") to intArrayOf(0, 0, 0, 0, 0),
            t("Bas") to intArrayOf(10, 6, 0, -2, -4),
            t("Tiz") to intArrayOf(-4, -2, 0, 6, 10),
            "Pop" to intArrayOf(-2, 4, 8, 4, -2),
            "Rock" to intArrayOf(6, 3, -2, 4, 6),
            t("Vokal") to intArrayOf(-4, 0, 8, 6, 0),
        )
    }

    private val bands = Prefs.eqBands.let { if (it.size == 5) it.copyOf() else intArrayOf(0, 0, 0, 0, 0) }
    private val sliders = arrayOfNulls<View>(5)
    private val valueLabels = arrayOfNulls<TextView>(5)

    override fun build(): View = with(ctx) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            for (i in 0 until 5) {
                addView(bandColumn(i), lp(0, MATCH, 1f).margins(l = if (i > 0) dp(8) else 0))
            }
        }

        val presets = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            PRESETS.forEach { (name, values) ->
                addView(pill(name) {
                    values.copyInto(bands)
                    save()
                    for (i in 0 until 5) updateSlider(i)
                }, lp(WRAP, dp(40)).margins(r = dp(8)))
            }
        }

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
            addView(label(t("Ön ayarlar"), 16f, Palette.TEXT_DIM, bold = true), lp(MATCH, WRAP).margins(b = dp(8)))
            presets.addView(pill(t("Sıfırla"), R.drawable.ic_refresh) {
                intArrayOf(0, 0, 0, 0, 0).copyInto(bands)
                save()
                for (i in 0 until 5) updateSlider(i)
            }, lp(WRAP, dp(40)).margins(l = dp(8)))
            addView(android.widget.HorizontalScrollView(this@with).apply {
                isHorizontalScrollBarEnabled = false
                addView(presets)
            }, lp(MATCH, WRAP).margins(b = dp(16)))
            addView(row, lp(MATCH, 0, 1f))
        }
    }

    private fun bandColumn(index: Int): View = with(ctx) {
        val valueLabel = label("${bands[index]} dB", 14f, Palette.TEXT_DIM, bold = true).apply {
            gravity = Gravity.CENTER
        }
        valueLabels[index] = valueLabel

        val thumbSize = dp(32)
        val track = FrameLayout(this).apply {
            background = rounded(0x33FFFFFF, dpf(4))
        }
        val fill = View(this).apply {
            background = rounded(Palette.accent, dpf(4))
        }
        val thumb = View(this).apply {
            background = rounded(Palette.accent, dpf(16), dp(2), 0x55FFFFFF)
        }

        // Skala sabit 200dp değil, sütunun gerçek yüksekliği: kısa araç ekranında kesilmesin, parmak ile düğme hizalı kalsın
        val slider = FrameLayout(this).apply {
            addView(track, flp(dp(6), MATCH, Gravity.CENTER_HORIZONTAL).margins(t = thumbSize / 2, b = thumbSize / 2))
            addView(fill, flp(dp(6), 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            addView(thumb, flp(thumbSize, thumbSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            sliders[index] = this
            addOnLayoutChangeListener { v, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) v.post { updateSlider(index) } }

            setOnTouchListener { v, ev ->
                if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                    val usable = (v.height - thumbSize).coerceAtLeast(1)
                    val fraction = 1f - ((ev.y - thumbSize / 2f) / usable).coerceIn(0f, 1f)
                    val level = Math.round(MIN_LEVEL + (MAX_LEVEL - MIN_LEVEL) * fraction).coerceIn(MIN_LEVEL, MAX_LEVEL)
                    if (level != bands[index]) {
                        bands[index] = level
                        updateSlider(index)
                        save()
                    }
                }
                true
            }
        }

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(valueLabel, lp(MATCH, WRAP).margins(b = dp(6)))
            addView(slider, lp(MATCH, 0, 1f))
            addView(label(BANDS[index], 13f, Palette.TEXT_FAINT).apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).margins(t = dp(6)))
        }
    }

    private fun updateSlider(index: Int) {
        valueLabels[index]?.text = "${bands[index]} dB"
        val slider = sliders[index] as? FrameLayout ?: return
        if (slider.height == 0) return
        val thumbSize = ctx.dp(32)
        val usable = (slider.height - thumbSize).coerceAtLeast(1)
        val fraction = (bands[index] - MIN_LEVEL).toFloat() / (MAX_LEVEL - MIN_LEVEL)
        slider.getChildAt(1).layoutParams = flp(ctx.dp(6), (usable * fraction).toInt().coerceAtLeast(1),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = thumbSize / 2)
        slider.getChildAt(2).layoutParams = flp(thumbSize, thumbSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (usable * (1f - fraction)).toInt()
        }
    }

    private fun save() {
        Prefs.eqBands = bands
        PlayerHub.applyEqualizer()
    }
}
