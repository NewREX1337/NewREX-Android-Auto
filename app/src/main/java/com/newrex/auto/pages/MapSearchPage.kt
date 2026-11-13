// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.location.Location
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.newrex.auto.DriveState
import com.newrex.auto.R
import com.newrex.auto.data.PlaceSearch
import com.newrex.auto.data.Prefs
import com.newrex.auto.t
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Harita araması sonuçları: dokununca o yer hedef olur ve haritaya dönülür. */
class MapSearchPage(ui: CarUi, private val query: String) : Page(ui) {
    override val title = t("Ara") + ": " + query

    override fun build(): View = with(ctx) {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(16)) }
        list.addView(label(t("Aranıyor…"), 16f, Palette.TEXT_DIM))
        val here = DriveState.bestLocation(this)
        scope.launch {
            val res = withContext(Dispatchers.IO) { PlaceSearch.search(query, here?.latitude, here?.longitude) }
            list.removeAllViews()
            when {
                res == null -> list.addView(label(t("İnternete bağlanılamadı, tekrar dene."), 16f, Palette.TEXT_DIM))
                res.isEmpty() -> list.addView(label(t("Sonuç bulunamadı."), 16f, Palette.TEXT_DIM))
                else -> res.forEach { p ->
                    val dist = here?.let {
                        val r = FloatArray(1)
                        Location.distanceBetween(it.latitude, it.longitude, p.lat, p.lng, r)
                        if (r[0] < 1000) "${r[0].toInt()} m" else String.format(com.newrex.auto.I18n.locale(), "%.1f km", r[0] / 1000f)
                    }
                    list.addView(panel(orientationVertical = false).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                        addView(icon(R.drawable.ic_place, 24, Palette.accent), lp(dp(24), dp(24)).margins(r = dp(14)))
                        addView(LinearLayout(this@with).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(label(p.name, 17f, bold = true, lines = 1))
                            if (p.address.isNotBlank()) addView(label(p.address, 13f, Palette.TEXT_DIM, lines = 2))
                        }, lp(0, WRAP, 1f))
                        if (dist != null) addView(label(dist, 15f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(l = dp(12)))
                        onTap {
                            Prefs.setDestination(p.lat, p.lng, p.name)
                            Prefs.dashCenter = 1
                            ui.back()
                        }
                    }, lp(MATCH, WRAP).margins(b = dp(10)))
                }
            }
            list.addView(pill(t("Başka bir şey ara"), R.drawable.ic_search, heightDp = 44) {
                ui.showKeyboard(t("Adres ya da yer ara"), initial = query) { q -> ui.back(); ui.push(MapSearchPage(ui, q)) }
            }, lp(WRAP, dp(44)).margins(t = dp(6)))
        }
        ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
    }
}
