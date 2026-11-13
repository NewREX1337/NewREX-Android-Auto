// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.newrex.auto.R
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.WebShortcut
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
import com.newrex.auto.ui.rounded

/** Ana sayfaya eklenen web kısayollarını yönetme sayfası (en fazla 6). */
class WebShortcutsPage(ui: CarUi) : Page(ui) {
    override val title = t("Web kısayolları")

    private var addName = ""
    private var addUrl = ""

    override fun build(): View = with(ctx) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val list = Prefs.webShortcuts
        if (list.isEmpty()) {
            content.addView(label(t("Henüz kısayol yok. Aşağıdan isim ve adres girip ekleyebilirsin; ana ekranda görünür."),
                16f, Palette.TEXT_DIM, lines = 2).apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).margins(b = dp(16)))
        } else {
            list.forEachIndexed { i, s ->
                content.addView(panel().apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    addView(icon(R.drawable.ic_language, 24, Palette.accent), lp(dp(28), dp(28)))
                    addView(LinearLayout(this@with).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(label(s.name, 16f, bold = true))
                        addView(label(s.url, 13f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(2)))
                    }, lp(0, WRAP, 1f).margins(l = dp(12)))
                    addView(icon(R.drawable.ic_close, 22, Palette.RED).apply {
                        onTap {
                            val updated = Prefs.webShortcuts.toMutableList()
                            if (i < updated.size) updated.removeAt(i)
                            Prefs.webShortcuts = updated
                            ui.pop(); ui.push(WebShortcutsPage(ui))
                        }
                    }, lp(dp(36), dp(36)))
                    layoutParams = lp(MATCH, WRAP).margins(b = dp(8))
                })
            }
        }

        if (list.size < 6) {
            content.addView(label(t("Yeni kısayol ekle"), 18f, bold = true), lp(MATCH, WRAP).margins(t = dp(16), b = dp(10)))
            val nameLabel = label(t("İsim: {0}", addName.ifBlank { "—" }), 15f)
            val urlLabel = label(t("Adres: {0}", addUrl.ifBlank { "—" }), 15f)

            content.addView(nameLabel, lp(MATCH, WRAP))
            content.addView(pill(t("İsim gir")) {
                ui.showKeyboard(t("Site ismi"), initial = addName) { v -> addName = v.trim(); nameLabel.text = t("İsim: {0}", addName.ifBlank { "—" }) }
            }, lp(WRAP, dp(44)).margins(t = dp(6)))

            content.addView(urlLabel, lp(MATCH, WRAP).margins(t = dp(12)))
            content.addView(pill(t("Adres gir")) {
                ui.showKeyboard("https://...", initial = addUrl) { v -> addUrl = v.trim(); urlLabel.text = t("Adres: {0}", addUrl.ifBlank { "—" }) }
            }, lp(WRAP, dp(44)).margins(t = dp(6)))

            content.addView(pill(t("Ekle"), R.drawable.ic_check, selected = true) {
                if (addName.isBlank() || addUrl.isBlank()) { ui.toast(t("Önce isim ve adres gir.")); return@pill }
                val url = if (!addUrl.startsWith("http")) "https://$addUrl" else addUrl
                val updated = Prefs.webShortcuts.toMutableList()
                updated += WebShortcut(addName, url)
                Prefs.webShortcuts = updated
                addName = ""; addUrl = ""
                ui.pop(); ui.push(WebShortcutsPage(ui))
            }, lp(WRAP, dp(44)).margins(t = dp(14)))
        } else {
            content.addView(label(t("En fazla 6 kısayol eklenebilir."), 14f, Palette.TEXT_FAINT),
                lp(MATCH, WRAP).margins(t = dp(12)))
        }

        ScrollView(this).apply { addView(content) }
    }
}
