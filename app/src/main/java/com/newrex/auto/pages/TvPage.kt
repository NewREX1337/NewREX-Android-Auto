// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Playlists
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.ListAdapter
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.tileGrid
import com.newrex.auto.ui.verticalList
import kotlinx.coroutines.launch

class TvPage(ui: CarUi) : Page(ui) {
    override val title = "Canlı TV"

    private companion object {
        const val FAV = "\u0000fav"
        const val ALL = "\u0000all"
        var lastGroup: String = ALL
    }

    private var channels: List<MediaEntry> = emptyList()
    private var query: String? = null
    private lateinit var groups: ListAdapter
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    override fun build(): View = with(ctx) {
        groups = ListAdapter(this) { key -> lastGroup = key; query = null; showGroup() }
        tiles = TileAdapter(this)
        status = label("", 16f, Palette.TEXT_DIM, lines = 4).apply { gravity = Gravity.CENTER }

        val left = panel().apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(verticalList(groups), lp(MATCH, MATCH))
        }
        val right = FrameLayout(this).apply {
            addView(tileGrid(tiles), flp(MATCH, MATCH))
            addView(status, flp(MATCH, WRAP, Gravity.CENTER).margins(dp(30), 0, dp(30), 0))
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(8), dp(4))
            addView(left, lp(dp(210), MATCH).margins(r = dp(8)))
            addView(right, lp(0, MATCH, 1f))
        }.also {
            scope.launch {
                ui.compact.collect { c ->
                    left.layoutParams = left.layoutParams.apply { width = dp(if (c) 140 else 210) }
                }
            }
            load(false)
            scope.launch { Prefs.changes.collect { if (::tiles.isInitialized) showGroup() } }
            scope.launch { PlayerHub.now.collect { tiles.highlightKey = it?.key } }
        }
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_search) {
            ui.showKeyboard("Kanal ara") { q -> query = q; showGroup() }
        },
        ctx.circleButton(R.drawable.ic_refresh) { load(true) },
    )

    private fun load(force: Boolean) {
        status.text = "Kanallar yükleniyor…"
        status.visibility = View.VISIBLE
        tiles.items = emptyList()
        scope.launch {
            val result = Playlists.loadAll(ctx, force)
            channels = result.channels
            val names = channels.map { it.group }.filter { it.isNotBlank() }.distinct().sortedBy { it.lowercase() }
            groups.items = listOf(FAV to "⭐ Favoriler", ALL to "Tüm kanallar") + names.map { it to it }
            if (lastGroup != FAV && lastGroup != ALL && lastGroup !in names) lastGroup = ALL
            showGroup()
            if (result.errors.isNotEmpty()) ui.toast("Bazı listeler açılamadı:\n" + result.errors.first())
        }
    }

    private fun showGroup() {
        groups.selected = if (query != null) null else lastGroup
        val favorites = Prefs.favorites(Kind.TV)
        val list = when {
            query != null -> channels.filter { it.title.contains(query!!, ignoreCase = true) }
            lastGroup == FAV -> favorites
            lastGroup == ALL -> channels
            else -> channels.filter { it.group == lastGroup }
        }
        val favKeys = favorites.map { it.key }.toSet()
        tiles.items = list.mapIndexed { i, ch ->
            Tile(ch.key, ch.title, ch.group.takeIf { lastGroup == ALL || query != null }, ch.image,
                R.drawable.ic_live_tv, favorite = ch.key in favKeys) {
                PlayerHub.play(list, i)
                ui.push(PlayerPage(ui))
            }
        }
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = when {
            channels.isEmpty() && Prefs.playlists.isEmpty() ->
                "Henüz IPTV listesi yok.\nTelefondaki NewREX uygulamasından liste ekleyebilirsin."
            channels.isEmpty() -> "Kanal bulunamadı. İnternet bağlantını kontrol edip yenile butonuna bas."
            query != null -> "\"$query\" için kanal bulunamadı."
            lastGroup == FAV -> "Henüz favori kanal yok.\nİzlerken ☆ simgesine dokunarak ekleyebilirsin."
            else -> "Bu grupta kanal yok."
        }
    }
}
