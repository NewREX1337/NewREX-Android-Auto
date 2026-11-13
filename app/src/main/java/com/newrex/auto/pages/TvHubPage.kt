// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.view.View
import com.newrex.auto.R
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.BrandArt
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.dp

import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded

/**
 * "Nereden izleyeceksin?": hazır kanallar, kendi IPTV listelerin, TV+ ve Netflix ayrı ayrı. Seçenek az olduğu
 * için kartlar ekranı dolduracak kadar büyük; araçta kolay dokunulsun.
 */
class TvHubPage(ui: CarUi) : Page(ui) {
    override val title = "TV"

    private class Item(val title: String, val subtitle: String, val color: Int, val art: (Float) -> Drawable, val onClick: () -> Unit)

    private lateinit var root: FrameLayout
    private var lastW = 0
    private var lastH = 0

    override fun build(): View = with(ctx) {
        root = FrameLayout(this).apply {
            addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val w = r - l; val h = b - t
                if (w > 0 && h > 0 && (w != lastW || h != lastH)) { lastW = w; lastH = h; v.post { fill() } }
            }
        }
        root
    }

    // Liste/sunucu ekleyip geri dönünce alt yazılardaki sayılar güncel kalsın
    override fun onShow() {
        if (lastW > 0) fill()
    }

    private fun items(): List<Item> {
        val mine = Prefs.playlists.count { it.url != Prefs.DEFAULT_PLAYLIST_URL }
        val xtreamCount = Prefs.xtreamServers.size
        return listOf(
            Item(t("TV Kanalları"), t("Ücretsiz Türk kanalları"), 0xFF0891B2.toInt(), { BrandArt.tvChannels(it) }) {
                ui.push(TvPage(ui, TvPage.Source.FREE))
            },
            Item(t("IPTV Listelerim"), if (mine > 0) t("{0} liste", mine) else t("Telefondan liste ekle"), 0xFF7C5CFF.toInt(),
                { BrandArt.iptv(it) }) { ui.push(TvPage(ui, TvPage.Source.MINE)) },
            Item("Xtream Codes", if (xtreamCount > 0) t("{0} sunucu", xtreamCount) else t("IPTV sunucusu ekle"), 0xFF7C5CFF.toInt(),
                { BrandArt.iptv(it) }) { ui.push(XtreamHubPage(ui)) },
            Item("TV+", t("Turkcell hesabınla"), 0xFFFFD60A.toInt(), { BrandArt.tvPlus(it) }) { ui.push(WebPage(ui, WebPage.Mode.TVPLUS)) },
            Item("Netflix", t("Netflix hesabınla"), 0xFFE50914.toInt(), { BrandArt.netflix(it) }) { ui.push(WebPage(ui, WebPage.Mode.NETFLIX)) },
            Item("Disney+", t("Disney+ hesabınla"), 0xFF1CA0D6.toInt(), { BrandArt.disney(it) }) { ui.push(WebPage(ui, WebPage.Mode.DISNEY)) },
            Item("Prime Video", t("Amazon hesabınla"), 0xFF00A8E1.toInt(), { BrandArt.prime(it) }) { ui.push(WebPage(ui, WebPage.Mode.PRIME)) },
        )
    }

    /** Ekran genişse 4 sütun 2 satır (ekranı doldurur), darsa 2 sütun ve kaydırma. */
    private fun fill(): Unit = with(ctx) {
        root.removeAllViews()
        val list = items()
        val pad = dp(12)
        val gap = dp(12)
        val cols = when {
            lastW < dp(560) -> 2
            lastW < dp(700) -> 3
            else -> 4
        }
        val rows = (list.size + cols - 1) / cols
        val rowH = maxOf(dp(110), (lastH - pad * 2 - gap * (rows - 1)) / rows)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        list.chunked(cols).forEachIndexed { ri, chunk ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until cols) {
                val v = chunk.getOrNull(i)?.let { card(it, rowH) } ?: View(this)
                row.addView(v, lp(0, MATCH, 1f).margins(l = if (i > 0) gap else 0))
            }
            grid.addView(row, lp(MATCH, rowH).margins(t = if (ri > 0) gap else 0))
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(grid, FrameLayout.LayoutParams(MATCH, WRAP))
        }, flp(MATCH, MATCH))
    }

    private fun card(item: Item, h: Int): View = with(ctx) {
        val r = dpf(20)
        val logo = (h * 0.42f).toInt().coerceIn(dp(48), dp(110))
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = pressable(rounded(0xFF0C0E14.toInt(), r, dp(1), Palette.withAlpha(item.color, 0x70)), r)
            addView(ImageView(this@with).apply {
                setImageDrawable(item.art(resources.displayMetrics.density))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, lp(logo, logo))
            addView(label(item.title, 22f, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(12)))
            addView(label(item.subtitle, 14f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(4)))
            onTap(item.onClick)
        }
    }
}
