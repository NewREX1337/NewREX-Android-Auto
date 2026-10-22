// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomePage(ui: CarUi) : Page(ui) {
    override val title = "Ana ekran"

    private class Shortcut(val title: String, val subtitle: String, val icon: Int, val color: Int, val onClick: () -> Unit)

    private lateinit var container: LinearLayout

    override fun build(): View = with(ctx) {
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(4))
        }
        scope.launch {
            combine(ui.compact, Prefs.changes) { c, _ -> c }.collect { populate(it) }
        }
        container
    }

    private fun populate(compact: Boolean) = with(ctx) {
        container.removeAllViews()
        val gap = dp(if (compact) 8 else 10)

        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        big.addView(bigCard("TV", "Canlı TV ve IPTV", R.drawable.ic_live_tv, 0xFF1D3A8A.toInt(), 0xFF2F80ED.toInt(),
            compact, live = true) { ui.push(TvPage(ui)) }, lp(0, MATCH, 1.15f).margins(r = gap))
        big.addView(bigCard("YouTube", "Video izle", R.drawable.ic_smart_display, 0xFF6B1420.toInt(), 0xFFE0263C.toInt(),
            compact) { ui.push(WebPage(ui, WebPage.Mode.YOUTUBE)) }, lp(0, MATCH, 1f).margins(r = gap))
        big.addView(bigCard("Radyo", "Türk radyoları", R.drawable.ic_radio, 0xFF3B1C7A.toInt(), 0xFF7C5CFF.toInt(),
            compact) { ui.push(RadioPage(ui)) }, lp(0, MATCH, 1f))

        val shortcuts = ArrayList<Shortcut>()
        shortcuts += Shortcut("Favoriler", "Kanal ve radyolar", R.drawable.ic_star, Palette.ORANGE) { ui.push(FavoritesPage(ui)) }
        Prefs.recents().firstOrNull()?.let { last ->
            shortcuts += Shortcut("Devam et", last.title, R.drawable.ic_history, Palette.GREEN) {
                val list = Prefs.recents().filter { it.kind == last.kind }
                PlayerHub.play(list, 0)
                if (last.kind == Kind.TV) ui.push(PlayerPage(ui))
            }
        }
        shortcuts += Shortcut("Web", "Tarayıcı", R.drawable.ic_language, 0xFF2F80ED.toInt()) { ui.push(WebPage(ui, WebPage.Mode.WEB)) }
        shortcuts += Shortcut("Ayarlar", "Tema ve listeler", R.drawable.ic_settings, 0xFF8B93A7.toInt()) { ui.push(SettingsPage(ui)) }
        // Dar ekranda yer yok; Hakkında sayfasına Ayarlar'dan da ulaşılabiliyor
        if (!compact) shortcuts += Shortcut("NewREX", "Hakkında", R.drawable.ic_info, Palette.accent) { ui.push(AboutPage(ui)) }

        val small = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shortcuts.forEachIndexed { i, s ->
            small.addView(smallCard(s, compact), lp(0, MATCH, 1f).margins(r = if (i < shortcuts.lastIndex) gap else 0))
        }

        container.addView(big, lp(MATCH, 0, 1.6f))
        container.addView(small, lp(MATCH, 0, if (compact) 0.8f else 1f).margins(t = gap))
    }

    private fun bigCard(
        title: String, subtitle: String, iconRes: Int, c1: Int, c2: Int,
        compact: Boolean, live: Boolean = false, onClick: () -> Unit,
    ): View = with(ctx) {
        val r = dpf(if (compact) 18 else 22)
        val pad = dp(if (compact) 12 else 16)
        FrameLayout(this).apply {
            background = pressable(gradient(c1, c2, r), r)
            clipToOutline = true
            // Arkada büyük, soluk simge
            val bgIcon = if (compact) 90 else 130
            addView(icon(iconRes, bgIcon).apply { alpha = 0.13f },
                flp(dp(bgIcon), dp(bgIcon), Gravity.END or Gravity.TOP).margins(t = dp(-10), r = dp(-18)))

            val badgeSize = if (compact) 40 else 52
            val badge = FrameLayout(this@with).apply {
                background = rounded(0x33FFFFFF, dpf(14))
                addView(icon(iconRes, if (compact) 22 else 28), flp(dp(if (compact) 22 else 28), dp(if (compact) 22 else 28), Gravity.CENTER))
            }
            addView(badge, flp(dp(badgeSize), dp(badgeSize)).margins(pad, pad))
            if (live && !compact) {
                addView(label("CANLI", 11f, bold = true).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    background = rounded(Palette.RED, dpf(6))
                }, flp(WRAP, WRAP).margins(l = dp(78), t = dp(32)))
            }

            val texts = LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                // Dar kartlarda başlık kesilmesin, sığacak kadar küçülsün
                addView(label(title, 28f, bold = true).apply {
                    setAutoSizeTextTypeUniformWithConfiguration(12, if (compact) 22 else 28, 1, TypedValue.COMPLEX_UNIT_DIP)
                }, lp(MATCH, dp(if (compact) 28 else 34)))
                if (!compact) addView(label(subtitle, 14f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(4)))
            }
            addView(texts, flp(MATCH, WRAP, Gravity.BOTTOM or Gravity.START)
                .margins(l = pad + dp(2), b = pad + dp(2), r = if (compact) pad else dp(62)))

            if (!compact) {
                val arrow = FrameLayout(this@with).apply {
                    background = rounded(0x33000000, dpf(19))
                    addView(icon(R.drawable.ic_chevron_right, 24), flp(dp(24), dp(24), Gravity.CENTER))
                }
                addView(arrow, flp(dp(38), dp(38), Gravity.BOTTOM or Gravity.END).margins(r = dp(16), b = dp(16)))
            }
            onTap(onClick)
        }
    }

    private fun smallCard(s: Shortcut, compact: Boolean): View = with(ctx) {
        val r = dpf(if (compact) 14 else 18)
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (compact) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 6 else 14), dp(8), dp(if (compact) 6 else 10), dp(8))
            background = pressable(rounded(Palette.SURFACE, r), r)
            val badgeSize = if (compact) 34 else 40
            val badge = FrameLayout(this@with).apply {
                background = rounded(Palette.withAlpha(s.color, 0x40), dpf(12))
                addView(icon(s.icon, 20, s.color), flp(dp(20), dp(20), Gravity.CENTER))
            }
            addView(badge, lp(dp(badgeSize), dp(badgeSize)))
            addView(label(s.title, if (compact) 13f else 16f, bold = true).apply {
                if (compact) gravity = Gravity.CENTER
            }, lp(MATCH, WRAP).margins(t = dp(if (compact) 6 else 10)))
            if (!compact) addView(label(s.subtitle, 12f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(3)))
            onTap(s.onClick)
        }
    }
}
