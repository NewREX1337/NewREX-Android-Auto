// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Logos
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.launch

/** Radyo listesi. [onPick] verilirse (FM ekranından) dokunulan istasyon çalınmaz, seçilip geri dönülür. */
class RadioPage(ui: CarUi, private val onPick: ((MediaEntry) -> Unit)? = null) : Page(ui) {
    override val title = if (onPick != null) "Bu frekansa istasyon seç" else "Radyo"

    private enum class Tab(val label: String) { POPULAR("Popüler"), FAVORITES("Favoriler"), RECENT("Son dinlenen"), SEARCH("Arama") }

    private companion object {
        // Sayfa her açıldığında tekrar indirmemek için
        var popularCache: List<MediaEntry> = emptyList()
    }

    private var tab = Tab.POPULAR
    private var searchResults: List<MediaEntry> = emptyList()
    private var searchQuery = ""
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        status = label("", 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val grid = FrameLayout(this).apply {
            addView(tileGrid(tiles, minTileDp = 140), flp(MATCH, MATCH))
            addView(status, flp(MATCH, WRAP, Gravity.CENTER).margins(dp(30), 0, dp(30), 0))
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            addView(HorizontalScrollView(this@with).apply {
                isHorizontalScrollBarEnabled = false
                addView(tabs)
            }, lp(MATCH, WRAP).margins(b = dp(8)))
            addView(grid, lp(MATCH, 0, 1f))
        }.also {
            refreshTabs()
            show()
            scope.launch { Prefs.changes.collect { if (tab != Tab.POPULAR && tab != Tab.SEARCH) show() else refreshTiles(currentList()) } }
            scope.launch { PlayerHub.now.collect { tiles.highlightKey = it?.key } }
        }
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_search) {
            ui.showKeyboard("Radyo ara (ör. Kral, Power, haber)") { q ->
                searchQuery = q
                tab = Tab.SEARCH
                refreshTabs()
                show()
            }
        },
    )

    private fun refreshTabs(): Unit = with(ctx) {
        tabs.removeAllViews()
        // Klasik FM radyo ekranı (frekansla arama) ayrı bir sayfada
        if (onPick == null) tabs.addView(pill("Frekansla ara", R.drawable.ic_radio, selected = false) { ui.push(FmTunerPage(ui)) }
            .apply { background = com.newrex.auto.ui.rounded(Palette.withAlpha(0xFFFF7A1A.toInt(), 0x55), dpf(20), dp(1), 0xFFFF7A1A.toInt()) },
            lp(WRAP, dp(40)).margins(r = dp(14)))
        Tab.values().filter { it != Tab.SEARCH || searchQuery.isNotEmpty() }.forEach { t ->
            val text = if (t == Tab.SEARCH) "Arama: $searchQuery" else t.label
            tabs.addView(pill(text, selected = t == tab) { tab = t; refreshTabs(); show() },
                lp(WRAP, dp(40)).margins(r = dp(8)))
        }
    }

    private fun currentList(): List<MediaEntry> = when (tab) {
        Tab.POPULAR -> popularCache
        Tab.FAVORITES -> Prefs.favorites(Kind.RADIO)
        Tab.RECENT -> Prefs.recents().filter { it.kind == Kind.RADIO }
        Tab.SEARCH -> searchResults
    }

    private fun show() {
        when (tab) {
            Tab.POPULAR -> if (popularCache.isEmpty()) {
                fetch("Radyolar yükleniyor…") { RadioApi.popular().also { popularCache = it } }
                return
            }
            Tab.SEARCH -> if (searchResults.isEmpty() || searchQuery != lastSearched) {
                fetch("\"$searchQuery\" aranıyor…") { RadioApi.search(searchQuery).also { searchResults = it; lastSearched = searchQuery } }
                return
            }
            else -> Unit
        }
        refreshTiles(currentList())
    }

    private var lastSearched = ""

    private fun fetch(message: String, block: suspend () -> List<MediaEntry>) {
        tiles.items = emptyList()
        status.text = message
        status.visibility = View.VISIBLE
        scope.launch {
            try {
                refreshTiles(block())
            } catch (e: Exception) {
                status.text = "Radyo listesi alınamadı. İnternet bağlantını kontrol et."
            }
        }
    }

    private var logoJob: kotlinx.coroutines.Job? = null
    private var checkedList: List<MediaEntry>? = null

    /** Logosu eksik/bozuk istasyonların logosunu arka planda bulur, bulundukça kutucuğu günceller. */
    private fun checkLogos(list: List<MediaEntry>) {
        if (list === checkedList) return
        checkedList = list
        logoJob?.cancel()
        logoJob = scope.launch {
            Logos.check(list) { e, logo ->
                val i = tiles.items.indexOfFirst { it.key == e.key }
                if (i >= 0) {
                    tiles.items = tiles.items.toMutableList().also { it[i] = it[i].copy(image = logo) }
                }
            }
        }
    }

    private fun refreshTiles(list: List<MediaEntry>) {
        val favKeys = Prefs.favorites(Kind.RADIO).map { it.key }.toSet()
        tiles.items = list.mapIndexed { i, st ->
            Tile(st.key, st.title, st.group.ifBlank { null }, Logos.best(st), R.drawable.ic_radio,
                favorite = st.key in favKeys, letters = true) {
                val pick = onPick
                if (pick != null) { ui.pop(); pick(st) } else PlayerHub.play(list, i)
            }
        }
        checkLogos(list)
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = when (tab) {
            Tab.FAVORITES -> "Henüz favori radyo yok.\nÇalarken alttaki ☆ simgesine dokun."
            Tab.RECENT -> "Henüz radyo dinlemedin."
            Tab.SEARCH -> "\"$searchQuery\" için sonuç bulunamadı."
            Tab.POPULAR -> "Radyo listesi şu an alınamadı.\nTekrar denemek için Popüler'e dokun."
        }
    }
}
