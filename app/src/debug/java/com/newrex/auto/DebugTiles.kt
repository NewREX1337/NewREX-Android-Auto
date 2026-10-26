// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.os.Bundle
import com.newrex.auto.data.Logos
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Sadece debug: radyo kutucuklarını (logolar / baş harfler) araç ekranı olmadan gösterir. */
class DebugTiles : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val tiles = TileAdapter(this)
        setContentView(tileGrid(tiles, minTileDp = 120).apply { setBackgroundColor(Palette.BG_TOP) })
        CoroutineScope(Dispatchers.Main).launch {
            val list = runCatching { RadioApi.popular() }.recoverCatching { RadioApi.popular() }.getOrElse { return@launch }
            tiles.items = list.map { Tile(it.key, it.title, it.group.ifBlank { null }, Logos.best(it), R.drawable.ic_radio, letters = true) {} }
            Logos.check(list) { e, logo ->
                val i = tiles.items.indexOfFirst { it.key == e.key }
                if (i >= 0) tiles.items = tiles.items.toMutableList().also { it[i] = it[i].copy(image = logo) }
            }
        }
    }
}
