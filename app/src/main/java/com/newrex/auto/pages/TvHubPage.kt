// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.View
import com.newrex.auto.R
import com.newrex.auto.data.Logos
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.launch

/** "Nereden izleyeceksin?": hazır kanallar, kendi IPTV listelerin, TV+ ve Netflix ayrı ayrı. */
class TvHubPage(ui: CarUi) : Page(ui) {
    override val title = "TV"

    override fun build(): View = with(ctx) {
        val tiles = TileAdapter(this)
        val mine = Prefs.playlists.count { it.url != Prefs.DEFAULT_PLAYLIST_URL }
        tiles.items = listOf(
            Tile("free", "TV Kanalları", "Ücretsiz Türk kanalları", null, R.drawable.ic_live_tv, tint = 0xFF06B6D4.toInt()) {
                ui.push(TvPage(ui, TvPage.Source.FREE))
            },
            Tile("mine", "IPTV Listelerim", if (mine > 0) "$mine liste" else "Telefondan liste ekle", null, R.drawable.ic_apps,
                tint = 0xFF7C5CFF.toInt()) {
                ui.push(TvPage(ui, TvPage.Source.MINE))
            },
            Tile(TVPLUS, "TV+", "Turkcell hesabınla", null, R.drawable.ic_smart_display, tint = 0xFFFFD60A.toInt()) {
                ui.push(WebPage(ui, WebPage.Mode.TVPLUS))
            },
            Tile(NETFLIX, "Netflix", "Netflix hesabınla", null, R.drawable.ic_smart_display, tint = 0xFFE50914.toInt()) {
                ui.push(WebPage(ui, WebPage.Mode.NETFLIX))
            },
        )
        // TV+ ve Netflix logoları kendi sitelerinden (yer imi simgesi gibi); bulunamazsa renkli simge kalır
        listOf(TVPLUS to "https://tvplus.com.tr/", NETFLIX to "https://www.netflix.com/").forEach { (key, site) ->
            scope.launch {
                val logo = Logos.site(site) ?: return@launch
                tiles.items = tiles.items.map { if (it.key == key) it.copy(image = logo) else it }
            }
        }
        tileGrid(tiles, minTileDp = 200).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
    }

    private companion object {
        const val TVPLUS = "tvplus"
        const val NETFLIX = "netflix"
    }
}
