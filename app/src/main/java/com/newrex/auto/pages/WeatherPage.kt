// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Yukarı aşağı kaydırılan hava durumu. Ölçüler ekranın gerçek yüksekliğinden hesaplanır;
 * böylece küçük ve düşük çözünürlüklü araç ekranlarında da yazılar büyük kalır.
 */
class WeatherPage(ui: CarUi) : Page(ui) {
    override val title = t("Hava durumu")

    private lateinit var body: FrameLayout
    private var report: WeatherApi.Report? = null
    private var error: String? = null
    private var scrollY = 0
    private var scroller: ScrollView? = null
    private var lastW = 0
    private var lastH = 0
    private var scale = 1f

    override fun build(): View = with(ctx) {
        body = FrameLayout(this).apply {
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val w = r - l
                val h = b - t
                if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                    lastW = w; lastH = h
                    v.post { render() }
                }
            }
        }
        scope.launch {
            combine(WeatherHub.report, WeatherHub.error) { r, e -> r to e }.collect { (r, e) ->
                report = r; error = e
                render()
            }
        }
        scope.launch { WeatherHub.refresh(ctx) }
        body
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_refresh) {
            ui.toast(t("Hava durumu yenileniyor…"))
            scope.launch { WeatherHub.refresh(ctx, force = true) }
        },
    )

    private fun u(v: Float) = (v * scale).toInt().coerceAtLeast(1)

    private fun text(s: CharSequence, size: Float, color: Int = Palette.TEXT, bold: Boolean = false, center: Boolean = false): TextView =
        ctx.label(s, size, color, bold).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
            if (center) gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun render(): Unit = with(ctx) {
        if (lastH == 0) return
        scale = maxOf(lastH / DESIGN_H, dpf(1f))
        scroller?.let { scrollY = it.scrollY }
        body.removeAllViews()
        scroller = null
        val r = report
        if (r == null) {
            body.addView(text(error ?: t("Hava durumu yükleniyor…"), 24f, Palette.TEXT_DIM, center = true).apply { maxLines = 3 }, flp(MATCH, WRAP, Gravity.CENTER))
            return
        }
        val narrow = lastW / scale < NARROW_W
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(u(12f), u(10f), u(12f), u(28f))
            addView(nowCard(r, narrow), lp(MATCH, WRAP))
            addView(heading(t("Saatlik")), lp(MATCH, WRAP).margins(t = u(18f), b = u(8f)))
            addView(HorizontalScrollView(this@with).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.HORIZONTAL
                    r.hours.forEachIndexed { i, h ->
                        addView(hourCell(if (i == 0) t("Şimdi") else h.label, WeatherApi.emoji(h.code, h.isDay), "${h.temp}°", h.rain),
                            lp(u(112f), WRAP).margins(r = if (i < r.hours.lastIndex) u(8f) else 0))
                    }
                }, lp(WRAP, WRAP))
            }, lp(MATCH, WRAP))
            addView(heading(t("Önümüzdeki günler")), lp(MATCH, WRAP).margins(t = u(18f), b = u(8f)))
            r.days.forEachIndexed { i, d ->
                addView(dayRow(d, narrow), lp(MATCH, u(78f)).margins(b = if (i < r.days.lastIndex) u(8f) else 0))
            }
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, flp(MATCH, WRAP))
        }
        body.addView(scroll, flp(MATCH, MATCH))
        scroller = scroll
        val keep = scrollY
        scroll.post { scroll.scrollTo(0, keep) }
    }

    private fun heading(s: String) = text(s, 24f, Palette.TEXT, bold = true)

    private fun nowCard(r: WeatherApi.Report, narrow: Boolean): View = with(ctx) {
        val main = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(WeatherApi.emoji(r.code, r.isDay), 78f), lp(WRAP, WRAP))
            addView(text("${r.temp}°", 84f, bold = true), lp(WRAP, WRAP).margins(l = u(12f)))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(WeatherApi.describe(r.code), 30f, bold = true).apply { maxLines = 2 }, lp(MATCH, WRAP))
            r.place?.let { addView(text("📍 $it", 24f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = u(6f))) }
        }
        // Bölünmüş ekranda yer adı sıkışmasın diye sıcaklığın altına iner
        val tiny = lastW / scale < TINY_W
        if (!tiny) main.addView(info, lp(0, WRAP, 1f).margins(l = u(20f)))
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(stat(t("Hissedilen"), "${r.feels}°"), lp(if (narrow) 0 else u(150f), WRAP, if (narrow) 1f else 0f))
            addView(stat(t("Rüzgâr"), "${r.wind} km/s"), lp(if (narrow) 0 else u(170f), WRAP, if (narrow) 1f else 0f))
            addView(stat(t("Nem"), "%${r.humidity}"), lp(if (narrow) 0 else u(110f), WRAP, if (narrow) 1f else 0f))
        }
        LinearLayout(this).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(20f), u(14f), u(20f), u(14f))
            background = rounded(Palette.SURFACE, u(18f).toFloat())
            if (narrow) {
                addView(main, lp(MATCH, WRAP))
                if (tiny) addView(info, lp(MATCH, WRAP).margins(t = u(6f)))
                addView(stats, lp(MATCH, WRAP).margins(t = u(12f)))
            } else {
                addView(main, lp(0, WRAP, 1f))
                addView(stats, lp(WRAP, WRAP).margins(l = u(12f)))
            }
        }
    }

    private fun stat(name: String, value: String): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text(name, 20f, Palette.TEXT_DIM, center = true), lp(MATCH, WRAP))
            addView(text(value, 28f, bold = true, center = true), lp(MATCH, WRAP).margins(t = u(4f)))
        }
    }

    private fun hourCell(top: String, emoji: String, temp: String, rain: Int): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(u(6f), u(12f), u(6f), u(12f))
            background = rounded(rainColor(rain), u(16f).toFloat())
            addView(text(top, 22f, Palette.TEXT_DIM, center = true), lp(MATCH, WRAP))
            addView(text(emoji, 42f, center = true), lp(MATCH, WRAP).margins(t = u(6f)))
            addView(text(temp, 32f, bold = true, center = true), lp(MATCH, WRAP).margins(t = u(6f)))
            addView(text(if (rain >= 20) "💧%$rain" else " ", 20f, RAIN_TEXT, center = true), lp(MATCH, WRAP).margins(t = u(4f)))
        }
    }

    private fun dayRow(d: WeatherApi.Day, narrow: Boolean): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(u(20f), 0, u(20f), 0)
            background = rounded(rainColor(d.rain), u(16f).toFloat())
            addView(text(d.label, 28f, bold = true), lp(0, WRAP, 1f))
            addView(text(WeatherApi.emoji(d.code), 42f, center = true), lp(u(if (narrow) 64f else 90f), WRAP))
            addView(text(if (d.rain >= 20) "💧%${d.rain}" else " ", 22f, RAIN_TEXT, center = true), lp(u(if (narrow) 96f else 130f), WRAP))
            addView(text("${d.max}°", 32f, bold = true).apply { gravity = Gravity.END }, lp(u(70f), WRAP))
            addView(text("${d.min}°", 28f, Palette.TEXT_DIM).apply { gravity = Gravity.END }, lp(u(64f), WRAP))
        }
    }

    private fun rainColor(rain: Int) = if (rain >= 60) Palette.withAlpha(0xFF2F80ED.toInt(), 0x40) else Palette.SURFACE

    private companion object {
        const val DESIGN_H = 430f
        const val NARROW_W = 760f
        const val TINY_W = 520f
        const val RAIN_TEXT = 0xFF7DB8FF.toInt()
    }
}
