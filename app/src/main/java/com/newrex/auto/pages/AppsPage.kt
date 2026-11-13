// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.VoiceCommand
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.gradient
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded

/** Uygulamadaki her bölüm, konusuna göre gruplanmış tek bir yerde. */
class AppsPage(ui: CarUi) : Page(ui) {
    override val title = t("Uygulamalar")

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_settings) { ui.push(SettingsPage(ui)) },
    )

    private class Item(val title: String, val subtitle: String, val icon: Int, val color: Int, val onClick: () -> Unit)

    private lateinit var column: LinearLayout
    private var lastWidth = 0

    override fun build(): View = with(ctx) {
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                if (r - l != lastWidth && r - l > 0) { lastWidth = r - l; v.post { fill() } }
            }
        }
    }

    override fun onShow() { if (lastWidth > 0) fill() }

    private fun sections(): List<Pair<String, List<Item>>> {
        val web = mutableListOf(
            Item("Web", t("İnternet tarayıcı"), R.drawable.ic_language, 0xFF2563EB.toInt()) { ui.push(WebPage(ui, WebPage.Mode.WEB)) },
        )
        Prefs.webShortcuts.forEach { ws ->
            web += Item(ws.name, ws.url.removePrefix("https://").removePrefix("http://").removePrefix("www."),
                R.drawable.ic_language, 0xFF0EA5E9.toInt()) { ui.push(WebPage(ui, WebPage.Mode.WEB, ws.url)) }
        }
        web += Item(t("Kısayol ekle"), t("Sık girdiğin siteler"), R.drawable.ic_add, 0xFF475569.toInt()) { ui.push(WebShortcutsPage(ui)) }
        return listOf(
            t("Telefon") to listOf(
                Item(t("Telefon uygulamaları"), t("Telefondaki tüm uygulamalar"), R.drawable.ic_apps, 0xFF0D9488.toInt()) { ui.push(PhoneAppsPage(ui)) },
            ),
            t("İzle") to listOf(
                Item("TV", t("Kanallar, IPTV, TV+"), R.drawable.ic_live_tv, 0xFF0EA5E9.toInt()) { ui.push(TvHubPage(ui)) },
                Item("YouTube", t("Video izle"), R.drawable.ic_smart_display, 0xFFE11D2E.toInt()) { ui.push(WebPage(ui, WebPage.Mode.YOUTUBE)) },
                Item(t("Medya"), t("Telefondaki video ve müzik"), R.drawable.ic_photo, 0xFF059669.toInt()) { ui.push(LocalMediaPage(ui)) },
                Item(t("Yansıt"), t("Telefon ekranını göster"), R.drawable.ic_cast, 0xFF15803D.toInt()) { ui.push(MirrorPage(ui)) },
            ),
            t("Dinle") to listOf(
                Item(t("Radyo"), t("Türk radyoları"), R.drawable.ic_radio, 0xFF8B3CF6.toInt()) { ui.push(RadioPage(ui)) },
                Item(t("FM radyo"), t("Frekansla ara"), R.drawable.ic_radio, 0xFFFF7A1A.toInt()) { ui.push(FmTunerPage(ui)) },
                Item("Podcast", t("Kaldığın yerden dinle"), R.drawable.ic_podcast, 0xFF6D28D9.toInt()) { ui.push(PodcastPage(ui)) },
                Item(t("Favoriler"), t("Kanal ve radyoların"), R.drawable.ic_star, 0xFFB45309.toInt()) { ui.push(FavoritesPage(ui)) },
            ),
            t("Araç ve yol") to listOf(
                Item(t("Araç"), t("Hız, yolculuk, bakım"), R.drawable.ic_car, 0xFFC42A35.toInt()) { ui.push(VehiclePage(ui)) },
                Item(t("Hava durumu"), t("Saatlik ve günlük"), R.drawable.ic_cloud, 0xFF64748B.toInt()) { ui.push(WeatherPage(ui)) },
                Item(t("Sesli komut"), t("\"TRT 1 aç\", \"hava durumu\", \"ayarlar\""), R.drawable.ic_mic, 0xFF0891B2.toInt()) { startVoice(ui) },
            ),
            t("İnternet") to web,
            t("Ayarlar") to listOf(
                Item(t("Ayarlar"), t("Tema, ses, sürüş"), R.drawable.ic_settings, 0xFF374151.toInt()) { ui.push(SettingsPage(ui)) },
                Item(t("Ekolayzır"), t("Sesi ayarla"), R.drawable.ic_tune, 0xFF7C3AED.toInt()) { ui.push(EqualizerPage(ui)) },
                Item(t("Hakkında"), t("Sürüm ve lisans"), R.drawable.ic_info, 0xFF334155.toInt()) { ui.push(AboutPage(ui)) },
            ),
        )
    }

    private fun fill(): Unit = with(ctx) {
        column.removeAllViews()
        val usable = lastWidth - column.paddingLeft - column.paddingRight
        val gap = dp(10)
        val cols = (usable / dp(210)).coerceIn(2, 4)
        sections().forEach { (name, items) ->
            column.addView(label(name, 17f, Palette.TEXT_DIM, bold = true), lp(MATCH, WRAP).margins(l = dp(4), t = dp(14), b = dp(8)))
            items.chunked(cols).forEachIndexed { i, rowItems ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (c in 0 until cols) {
                    val v = rowItems.getOrNull(c)?.let { tile(it) } ?: View(this)
                    row.addView(v, lp(0, MATCH, 1f).margins(r = if (c < cols - 1) gap else 0))
                }
                column.addView(row, lp(MATCH, dp(76)).margins(t = if (i > 0) gap else 0))
            }
        }
    }

    private fun tile(item: Item): View = with(ctx) {
        val r = dpf(16)
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(rounded(0xFF0C0E14.toInt(), r, 1, 0x1FFFFFFF), r)
            setPadding(dp(12), 0, dp(10), 0)
            addView(FrameLayout(this@with).apply {
                background = gradient(Palette.withAlpha(item.color, 0xFF), Palette.withAlpha(item.color, 0xB0), dpf(12),
                    GradientDrawable.Orientation.TOP_BOTTOM)
                addView(icon(item.icon, 24), flp(dp(24), dp(24), Gravity.CENTER))
            }, lp(dp(46), dp(46)))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(item.title, 17f, bold = true))
                addView(label(item.subtitle, 13f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(3)))
            }, lp(0, WRAP, 1f).margins(l = dp(12)))
            onTap(item.onClick)
        }
    }
}

/** Çalanı duraklatır/sürdürür; hiçbir şey çalmıyorsa son açılanı başlatır. */
fun resumeLast(ui: CarUi) {
    if (PlayerHub.now.value != null) { PlayerHub.toggle(); return }
    val last = Prefs.recents().firstOrNull() ?: run { ui.push(TvHubPage(ui)); return }
    PlayerHub.play(Prefs.recents().filter { it.kind == last.kind }, 0)
    if (last.kind == Kind.TV) ui.push(PlayerPage(ui))
}
