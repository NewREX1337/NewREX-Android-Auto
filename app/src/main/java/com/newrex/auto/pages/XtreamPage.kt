// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.XtreamApi
import com.newrex.auto.data.XtreamServer
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.launch

/** Xtream Codes sunucu listesi: kayıtlı sunucular + yeni ekle. */
class XtreamHubPage(ui: CarUi) : Page(ui) {
    override val title = "Xtream Codes"

    override fun build(): View = with(ctx) {
        val tiles = TileAdapter(this)
        fun refresh() {
            val servers = Prefs.xtreamServers
            tiles.items = servers.mapIndexed { i, s ->
                Tile("xtream_$i", s.name.ifBlank { t("Sunucu {0}", i + 1) }, s.url, null, R.drawable.ic_live_tv) {
                    ui.push(XtreamServerPage(ui, s))
                }
            } + Tile("xtream_add", t("Sunucu ekle"), t("URL, kullanıcı adı, şifre"), null, R.drawable.ic_add) {
                ui.push(XtreamAddPage(ui) { ui.pop(); ui.push(XtreamHubPage(ui)) })
            }
        }
        refresh()
        tileGrid(tiles, minTileDp = 200).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
    }
}

/** Yeni Xtream Codes sunucusu ekleme sayfası. */
class XtreamAddPage(ui: CarUi, private val onDone: () -> Unit) : Page(ui) {
    override val title = t("Sunucu ekle")

    private var serverUrl = ""
    private var username = ""
    private var password = ""
    private var serverName = ""

    override fun build(): View = with(ctx) {
        val status = label("", 15f, Palette.TEXT_DIM, lines = 2).apply { gravity = Gravity.CENTER }
        val urlLabel = label(t("Sunucu: —"), 16f, bold = true)
        val userLabel = label(t("Kullanıcı: —"), 16f, bold = true)
        val passLabel = label(t("Şifre: —"), 16f, bold = true)
        val nameLabel = label(t("İsim: —"), 16f, bold = true)

        fun updateLabels() {
            urlLabel.text = t("Sunucu: {0}", serverUrl.ifBlank { "—" })
            userLabel.text = t("Kullanıcı: {0}", username.ifBlank { "—" })
            passLabel.text = t("Şifre: {0}", if (password.isNotBlank()) "••••" else "—")
            nameLabel.text = t("İsim: {0}", serverName.ifBlank { "—" })
        }

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))

            addView(urlLabel, lp(MATCH, WRAP))
            addView(pill(t("Sunucu adresi gir")) {
                ui.showKeyboard("http://sunucu:port", initial = serverUrl) { v ->
                    serverUrl = v.trim()
                    updateLabels()
                }
            }, lp(WRAP, dp(44)).margins(t = dp(8)))

            addView(userLabel, lp(MATCH, WRAP).margins(t = dp(16)))
            addView(pill(t("Kullanıcı adı gir")) {
                ui.showKeyboard(t("Kullanıcı adı"), initial = username) { v ->
                    username = v.trim()
                    updateLabels()
                }
            }, lp(WRAP, dp(44)).margins(t = dp(8)))

            addView(passLabel, lp(MATCH, WRAP).margins(t = dp(16)))
            addView(pill(t("Şifre gir")) {
                ui.showKeyboard(t("Şifre"), initial = password, secret = true) { v ->
                    password = v.trim()
                    updateLabels()
                }
            }, lp(WRAP, dp(44)).margins(t = dp(8)))

            addView(nameLabel, lp(MATCH, WRAP).margins(t = dp(16)))
            addView(pill(t("İsim gir (isteğe bağlı)")) {
                ui.showKeyboard(t("Sunucu ismi"), initial = serverName) { v ->
                    serverName = v.trim()
                    updateLabels()
                }
            }, lp(WRAP, dp(44)).margins(t = dp(8)))

            addView(status, lp(MATCH, WRAP).margins(t = dp(16)))

            addView(pill(t("Bağlantıyı test et ve kaydet"), R.drawable.ic_check, selected = true) {
                if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
                    status.text = t("Sunucu, kullanıcı adı ve şifre zorunlu.")
                    status.setTextColor(Palette.RED)
                    return@pill
                }
                val server = XtreamServer(serverName.ifBlank { serverUrl }, serverUrl, username, password)
                status.text = t("Bağlanıyor…")
                status.setTextColor(Palette.TEXT_DIM)
                scope.launch {
                    val ok = XtreamApi.authenticate(server)
                    if (ok) {
                        val list = Prefs.xtreamServers.toMutableList()
                        list += server
                        Prefs.xtreamServers = list
                        status.text = t("Bağlantı başarılı! Kaydedildi.")
                        status.setTextColor(Palette.GREEN)
                        ui.pop()
                        onDone()
                    } else {
                        status.text = t("Bağlantı başarısız. Bilgileri kontrol et.")
                        status.setTextColor(Palette.RED)
                    }
                }
            }, lp(WRAP, dp(44)).margins(t = dp(16)))
        }.let { content ->
            ScrollView(this).apply { addView(content) }
        }
    }
}

/** Bir Xtream Codes sunucusunun içeriği: Canlı TV, Filmler, Diziler. */
class XtreamServerPage(ui: CarUi, private val server: XtreamServer) : Page(ui) {
    override val title = server.name.ifBlank { "Xtream" }

    override fun build(): View = with(ctx) {
        val tiles = TileAdapter(this)
        tiles.items = listOf(
            Tile("live", t("Canlı TV"), t("Canlı kanallar"), null, R.drawable.ic_live_tv) {
                ui.push(XtreamCategoryPage(ui, server, XtreamCategoryPage.Type.LIVE))
            },
            Tile("vod", t("Filmler"), t("Film arşivi"), null, R.drawable.ic_smart_display) {
                ui.push(XtreamCategoryPage(ui, server, XtreamCategoryPage.Type.VOD))
            },
            Tile("series", t("Diziler"), t("Dizi arşivi"), null, R.drawable.ic_smart_display) {
                ui.push(XtreamCategoryPage(ui, server, XtreamCategoryPage.Type.SERIES))
            },
        )
        tileGrid(tiles, minTileDp = 200).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
    }
}

/** Xtream Codes kategori ve kanal listesi. */
class XtreamCategoryPage(ui: CarUi, private val server: XtreamServer, private val type: Type) : Page(ui) {
    enum class Type { LIVE, VOD, SERIES }
    override val title = when (type) { Type.LIVE -> t("Canlı TV"); Type.VOD -> t("Filmler"); Type.SERIES -> t("Diziler") }

    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView
    private var currentCategory: String? = null

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        status = label(t("Yükleniyor…"), 16f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }
        FrameLayout(this).apply {
            addView(tileGrid(tiles).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }, flp(MATCH, MATCH))
            addView(status, flp(MATCH, WRAP, Gravity.CENTER))
        }.also { loadCategories() }
    }

    private var job: kotlinx.coroutines.Job? = null

    // Bir kategorinin içindeyken geri tuşu sayfadan çıkmasın, kategori listesine dönsün
    override fun onBack(): Boolean {
        if (currentCategory == null) return false
        currentCategory = null
        status.visibility = View.VISIBLE
        status.text = t("Yükleniyor…")
        status.setTextColor(Palette.TEXT_DIM)
        tiles.items = emptyList()
        loadCategories()
        return true
    }

    private fun loadCategories() {
        job?.cancel()
        job = scope.launch {
            try {
                val categories = when (type) {
                    Type.LIVE -> XtreamApi.liveCategories(server)
                    Type.VOD -> XtreamApi.vodCategories(server)
                    Type.SERIES -> XtreamApi.seriesCategories(server)
                }
                status.visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
                if (categories.isEmpty()) status.text = t("Bu sunucuda kategori bulunamadı.")
                tiles.items = categories.map { cat ->
                    Tile("cat_${cat.id}", cat.name, "", null, R.drawable.ic_apps) {
                        currentCategory = cat.id
                        loadContent(cat.id, cat.name)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = t("Yüklenemedi: {0}", e.message)
                status.setTextColor(Palette.RED)
            }
        }
    }

    private fun loadContent(categoryId: String, categoryName: String) {
        status.visibility = View.VISIBLE
        status.text = t("Yükleniyor…")
        status.setTextColor(Palette.TEXT_DIM)
        tiles.items = emptyList()
        job?.cancel()
        job = scope.launch {
            try {
                when (type) {
                    Type.LIVE -> {
                        val channels = XtreamApi.liveStreams(server, categoryId)
                        status.visibility = View.GONE
                        val entries = channels.map { ch ->
                            MediaEntry(Kind.TV, ch.name, ch.url, ch.icon, categoryName)
                        }
                        tiles.items = channels.mapIndexed { i, ch ->
                            Tile("ch_${ch.id}", ch.name, "", ch.icon, R.drawable.ic_live_tv) {
                                PlayerHub.play(entries, i)
                                ui.push(PlayerPage(ui))
                            }
                        }
                    }
                    Type.VOD -> {
                        val vods = XtreamApi.vodStreams(server, categoryId)
                        status.visibility = View.GONE
                        tiles.items = vods.map { vod ->
                            Tile("vod_${vod.id}", vod.name, "", vod.icon, R.drawable.ic_smart_display) {
                                val entry = MediaEntry(Kind.TV, vod.name, vod.url, vod.icon, categoryName)
                                PlayerHub.play(listOf(entry), 0)
                                ui.push(PlayerPage(ui))
                            }
                        }
                    }
                    Type.SERIES -> {
                        val series = XtreamApi.seriesList(server, categoryId)
                        status.visibility = View.GONE
                        tiles.items = series.map { s ->
                            Tile("ser_${s.id}", s.name, "", s.icon, R.drawable.ic_smart_display) {
                                ui.push(XtreamEpisodesPage(ui, server, s.id, s.name))
                            }
                        }
                    }
                }
                if (tiles.items.isEmpty()) {
                    status.text = t("Bu kategoride içerik yok.")
                    status.visibility = View.VISIBLE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = t("Yüklenemedi: {0}", e.message)
                status.setTextColor(Palette.RED)
                status.visibility = View.VISIBLE
            }
        }
    }
}

/** Xtream Codes dizi bölümleri sayfası. */
class XtreamEpisodesPage(ui: CarUi, private val server: XtreamServer, private val seriesId: String, override val title: String) : Page(ui) {

    override fun build(): View = with(ctx) {
        val tiles = TileAdapter(this)
        val status = label(t("Yükleniyor…"), 16f, Palette.TEXT_DIM).apply { gravity = Gravity.CENTER }
        FrameLayout(this).apply {
            addView(tileGrid(tiles).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }, flp(MATCH, MATCH))
            addView(status, flp(MATCH, WRAP, Gravity.CENTER))
        }.also {
            scope.launch {
                try {
                    val episodes = XtreamApi.seriesEpisodes(server, seriesId)
                    status.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
                    if (episodes.isEmpty()) status.text = t("Bu dizide bölüm bulunamadı.")
                    val entries = episodes.map { ep -> MediaEntry(Kind.TV, ep.title, ep.url, null, title) }
                    tiles.items = episodes.mapIndexed { i, ep ->
                        Tile("ep_${ep.id}", ep.title, "", null, R.drawable.ic_smart_display) {
                            PlayerHub.play(entries, i)
                            ui.push(PlayerPage(ui))
                        }
                    }
                } catch (e: Exception) {
                    status.text = t("Yüklenemedi: {0}", e.message)
                    status.setTextColor(Palette.RED)
                }
            }
        }
    }
}
