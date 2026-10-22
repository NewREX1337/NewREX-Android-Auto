// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.Brand
import com.newrex.auto.BuildConfig
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Playlists
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.launch

/** Favori kanallar, radyolar ve son çalınanlar tek yerde. */
class FavoritesPage(ui: CarUi) : Page(ui) {
    override val title = "Favoriler"

    private enum class Tab(val label: String) { ALL("Tüm favoriler"), TV("TV"), RADIO("Radyo"), RECENT("Son çalınanlar") }

    private var tab = Tab.ALL
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = label("", 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
                lp(MATCH, WRAP).margins(b = dp(8)))
            addView(FrameLayout(this@with).apply {
                addView(tileGrid(tiles), flp(MATCH, MATCH))
                addView(status, flp(MATCH, WRAP, Gravity.CENTER))
            }, lp(MATCH, 0, 1f))
        }.also {
            scope.launch { Prefs.changes.collect { show() } }
            scope.launch { PlayerHub.now.collect { tiles.highlightKey = it?.key } }
        }
    }

    private fun show(): Unit = with(ctx) {
        tabs.removeAllViews()
        Tab.values().forEach { t ->
            tabs.addView(pill(t.label, selected = t == tab) { tab = t; show() }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        val list: List<MediaEntry> = when (tab) {
            Tab.ALL -> Prefs.favorites()
            Tab.TV -> Prefs.favorites(Kind.TV)
            Tab.RADIO -> Prefs.favorites(Kind.RADIO)
            Tab.RECENT -> Prefs.recents()
        }
        val favKeys = Prefs.favorites().map { it.key }.toSet()
        tiles.items = list.mapIndexed { i, e ->
            Tile(e.key, e.title, when (e.kind) { Kind.TV -> "TV"; Kind.RADIO -> "Radyo"; Kind.PODCAST -> "Podcast" }, e.image,
                com.newrex.auto.ui.kindIcon(e.kind),
                favorite = e.key in favKeys) {
                // Aynı türden öğeler arasında ileri/geri geçilebilsin
                val sameKind = list.filter { it.kind == e.kind }
                PlayerHub.play(sameKind, sameKind.indexOf(e))
                if (e.kind == Kind.TV) ui.push(PlayerPage(ui))
            }
        }
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (tab == Tab.RECENT) "Henüz bir şey çalmadın."
        else "Henüz favori yok.\nÇalarken ☆ simgesine dokunarak ekleyebilirsin."
    }
}

class SettingsPage(ui: CarUi) : Page(ui) {
    override val title = "Ayarlar"

    override fun build(): View = with(ctx) {
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Palette.accents.forEachIndexed { i, c ->
            val selected = i == Prefs.accentIndex
            colors.addView(FrameLayout(this@with).apply {
                val r = dpf(24)
                background = pressable(rounded(c, r, if (selected) dp(4) else 0, Palette.TEXT), r)
                onTap {
                    Prefs.accentIndex = i
                    ui.rebuild()
                }
            }, lp(dp(48), dp(48)).margins(r = dp(14)))
        }

        val playlistInfo = label("", 15f, Palette.TEXT_DIM, lines = 6)
        playlistInfo.text = Prefs.playlists.joinToString("\n") { "• ${it.name}" }
            .ifEmpty { "Henüz liste yok." }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(section("Tema rengi", colors))
            addView(section("IPTV listeleri", LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(playlistInfo)
                addView(label("Liste eklemek veya silmek için telefonda NewREX uygulamasını aç.", 13f, Palette.TEXT_FAINT, lines = 2),
                    lp(MATCH, WRAP).margins(t = dp(8)))
                addView(pill("Kanalları yeniden indir", R.drawable.ic_refresh) {
                    scope.launch {
                        ui.toast("Listeler yenileniyor…")
                        val r = Playlists.loadAll(ctx, forceRefresh = true)
                        ui.toast("${r.channels.size} kanal yüklendi" + if (r.errors.isNotEmpty()) " (${r.errors.size} liste hatalı)" else "")
                    }
                }, lp(WRAP, dp(44)).margins(t = dp(12)))
            }))
            addView(section("Park modu", LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                val toggleLabel = label("", 15f, bold = true)
                fun updateToggle() {
                    val on = Prefs.parkModeVideo
                    toggleLabel.text = if (on) "Sürüşte video gizlenir" else "Sürüşte video açık kalır"
                    toggleLabel.setTextColor(if (on) Palette.GREEN else Palette.ORANGE)
                }
                updateToggle()
                addView(toggleLabel, lp(MATCH, WRAP))
                addView(pill(
                    if (Prefs.parkModeVideo) "Kapatmak için dokun" else "Açmak için dokun",
                    res = if (Prefs.parkModeVideo) R.drawable.ic_parking else R.drawable.ic_car,
                ) {
                    Prefs.parkModeVideo = !Prefs.parkModeVideo
                    ui.pop()
                    ui.push(SettingsPage(ui))
                }, lp(WRAP, dp(44)).margins(t = dp(10)))
                addView(label(
                    "Açıkken araç hareket edince video ve web görüntüsü gizlenir, ses devam eder. " +
                        "Kapatırsan sürüşte de görüntü açık kalır. Dikkatli kullan!",
                    13f, Palette.TEXT_DIM, lines = 4), lp(MATCH, WRAP).margins(t = dp(10)))
                addView(label(
                    "Hız, araçtan alınamazsa telefonun GPS'inden ölçülür (telefonda konum izni gerekir).",
                    13f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(6)))
            }))
            addView(pill("NewREX hakkında", R.drawable.ic_info) { ui.push(AboutPage(ui)) }, lp(WRAP, dp(44)))
        }
        ScrollView(this).apply { addView(content) }
    }

    private fun section(title: String, body: View): View = with(ctx) {
        panel().apply {
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(label(title, 18f, bold = true), lp(MATCH, WRAP).margins(b = dp(12)))
            addView(body, lp(MATCH, WRAP))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(12))
        }
    }
}

class AboutPage(ui: CarUi) : Page(ui) {
    override val title = "Hakkında"

    override fun build(): View = with(ctx) {
        val logo = SpannableStringBuilder("NewREX").apply {
            setSpan(ForegroundColorSpan(Palette.accent), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label(logo, 52f, bold = true), lp(WRAP, WRAP))
            addView(label("Android Auto", 20f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(2)))
            addView(label("Sürüm ${BuildConfig.VERSION_NAME}", 14f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(t = dp(14)))
            addView(label("Orijinal geliştirici: ${Brand.ORIGINAL_DEVELOPER}", 18f, bold = true), lp(WRAP, WRAP).margins(t = dp(16)))
            addView(label("Reklam yok  •  Takip yok  •  Veri toplanmaz", 15f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(8)))
            addView(label(Brand.COPYRIGHT, 13f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(t = dp(10)))
            addView(label(Brand.LICENSE_NOTICE, 12f, Palette.TEXT_FAINT, lines = 3).apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).margins(dp(30), dp(4), dp(30), 0))
        }
    }
}
