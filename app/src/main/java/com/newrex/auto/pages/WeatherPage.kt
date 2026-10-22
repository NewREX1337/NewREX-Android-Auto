// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.R
import com.newrex.auto.WeatherHub
import com.newrex.auto.data.WeatherApi
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.neonText
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class WeatherPage(ui: CarUi) : Page(ui) {
    override val title = "Hava durumu"

    private lateinit var body: FrameLayout

    override fun build(): View = with(ctx) {
        body = FrameLayout(this).apply { setPadding(dp(12), dp(6), dp(12), dp(6)) }
        scope.launch {
            combine(WeatherHub.report, WeatherHub.error, ui.compact) { r, e, c -> Triple(r, e, c) }
                .collect { (r, e, c) -> render(r, e, c) }
        }
        scope.launch { WeatherHub.refresh(ctx) }
        body
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_refresh) {
            ui.toast("Hava durumu yenileniyor…")
            scope.launch { WeatherHub.refresh(ctx, force = true) }
        },
    )

    private fun render(r: WeatherApi.Report?, error: String?, compact: Boolean): Unit = with(ctx) {
        body.removeAllViews()
        if (r == null) {
            body.addView(label(error ?: "Hava durumu yükleniyor…", 17f, Palette.TEXT_DIM, lines = 3).apply {
                gravity = Gravity.CENTER
            }, flp(MATCH, WRAP, Gravity.CENTER))
            return
        }
        val now = panel().apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(label(WeatherApi.emoji(r.code, r.isDay), if (compact) 40f else 56f).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
            addView(label("${r.temp}°", if (compact) 40f else 52f, bold = true).apply { neonText(14) }, lp(WRAP, WRAP).margins(t = dp(2)))
            addView(label(WeatherApi.describe(r.code), 16f, bold = true), lp(WRAP, WRAP).margins(t = dp(2)))
            r.place?.let { addView(label("📍 $it", 13f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(4))) }
            addView(label("Hissedilen ${r.feels}°  •  💨 ${r.wind} km/s  •  💧 %${r.humidity}", 12f, Palette.TEXT_DIM, lines = 2)
                .apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(6)))
        }

        val hours = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.hours.forEachIndexed { i, h ->
            hours.addView(cell(if (i == 0) "Şimdi" else h.label, WeatherApi.emoji(h.code, h.isDay), "${h.temp}°",
                if (h.rain >= 20) "💧%${h.rain}" else " ", h.rain >= 60), lp(dp(66), MATCH).margins(r = dp(6)))
        }
        val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        r.days.forEachIndexed { i, d ->
            days.addView(cell(d.label, WeatherApi.emoji(d.code), "${d.max}° / ${d.min}°",
                if (d.rain >= 20) "💧%${d.rain}" else " ", d.rain >= 60), lp(0, MATCH, 1f).margins(r = if (i < r.days.lastIndex) dp(6) else 0))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Saatlik", 14f, Palette.TEXT_DIM, bold = true), lp(MATCH, WRAP).margins(b = dp(4)))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(hours, lp(WRAP, MATCH)) }, lp(MATCH, 0, 1f))
            addView(label("Önümüzdeki günler", 14f, Palette.TEXT_DIM, bold = true), lp(MATCH, WRAP).margins(t = dp(8), b = dp(4)))
            addView(days, lp(MATCH, 0, 1f))
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(now, lp(dp(if (compact) 150 else 220), MATCH).margins(r = dp(10)))
            addView(right, lp(0, MATCH, 1f))
        }, flp(MATCH, MATCH))
    }

    private fun cell(top: String, emoji: String, main: String, sub: String, warn: Boolean): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val c = if (warn) Palette.withAlpha(0xFF2F80ED.toInt(), 0x40) else Palette.SURFACE
            background = rounded(c, dpf(14))
            addView(label(top, 12f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
            addView(label(emoji, 20f).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(2)))
            addView(label(main, 13f, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(2)))
            addView(label(sub, 11f, 0xFF7DB8FF.toInt()).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(1)))
        }
    }
}
