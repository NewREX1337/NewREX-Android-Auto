// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.PodcastApi
import com.newrex.auto.data.PodcastShow
import com.newrex.auto.data.Prefs
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
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.neonGlow
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded
import com.newrex.auto.ui.tileGrid
import com.newrex.auto.ui.verticalList
import kotlinx.coroutines.launch

/** Podcast programları: popüler listesi, abonelikler, son dinlenenler ve arama. */
class PodcastPage(ui: CarUi) : Page(ui) {
    override val title = "Podcast"

    private enum class Tab(val label: String) { POPULAR("Popüler"), SUBS("Aboneliklerim"), RECENT("Son dinlenen"), SEARCH("Arama") }

    private companion object {
        var topCache: List<PodcastShow> = emptyList()
    }

    private var tab = if (Prefs.podcastSubs.isNotEmpty()) Tab.SUBS else Tab.POPULAR
    private var query = ""
    private var results: List<PodcastShow> = emptyList()
    private var lastSearched = ""
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        status = label("", 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
                lp(MATCH, WRAP).margins(b = dp(8)))
            addView(FrameLayout(this@with).apply {
                addView(tileGrid(tiles, minTileDp = 140), flp(MATCH, MATCH))
                addView(status, flp(MATCH, WRAP, Gravity.CENTER).margins(dp(30), 0, dp(30), 0))
            }, lp(MATCH, 0, 1f))
        }.also {
            refreshTabs()
            show()
            scope.launch { Prefs.changes.collect { if (tab == Tab.SUBS || tab == Tab.RECENT) show() } }
            scope.launch { PlayerHub.now.collect { tiles.highlightKey = it?.key } }
        }
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_search) {
            ui.showKeyboard("Podcast ara (ör. tarih, teknoloji, futbol)") { q ->
                query = q
                tab = Tab.SEARCH
                refreshTabs()
                show()
            }
        },
    )

    private fun refreshTabs(): Unit = with(ctx) {
        tabs.removeAllViews()
        Tab.values().filter { it != Tab.SEARCH || query.isNotEmpty() }.forEach { t ->
            val text = if (t == Tab.SEARCH) "Arama: $query" else t.label
            tabs.addView(pill(text, selected = t == tab) { tab = t; refreshTabs(); show() }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
    }

    private fun show() {
        when (tab) {
            Tab.POPULAR -> if (topCache.isEmpty()) {
                load("Popüler podcast'ler yükleniyor…") { PodcastApi.top().also { topCache = it } }
            } else showShows(topCache)
            Tab.SUBS -> showShows(Prefs.podcastSubs)
            Tab.SEARCH -> if (query != lastSearched) {
                load("\"$query\" aranıyor…") { PodcastApi.search(query).also { results = it; lastSearched = query } }
            } else showShows(results)
            Tab.RECENT -> {
                val eps = Prefs.recents().filter { it.kind == Kind.PODCAST }
                tiles.items = eps.mapIndexed { i, e ->
                    Tile(e.key, e.title, e.group, e.image, R.drawable.ic_podcast) { PlayerHub.play(eps, i) }
                }
                setStatus(eps.isEmpty(), "Henüz podcast dinlemedin.")
            }
        }
    }

    private fun load(message: String, block: suspend () -> List<PodcastShow>) {
        tiles.items = emptyList()
        setStatus(true, message)
        scope.launch {
            try {
                showShows(block())
            } catch (e: Exception) {
                setStatus(true, "Podcast listesi alınamadı. İnternet bağlantını kontrol et.")
            }
        }
    }

    private fun showShows(list: List<PodcastShow>) {
        val subs = Prefs.podcastSubs.map { it.id }.toSet()
        tiles.items = list.map { s ->
            Tile("show|${s.id}", s.name, s.author.ifBlank { null }, s.image, R.drawable.ic_podcast, favorite = s.id in subs) {
                ui.push(PodcastShowPage(ui, s))
            }
        }
        setStatus(list.isEmpty(), when (tab) {
            Tab.SUBS -> "Henüz abone olduğun podcast yok.\nBir programı açıp ☆ Abone ol'a dokun."
            Tab.SEARCH -> "\"$query\" için sonuç bulunamadı."
            else -> "Podcast bulunamadı."
        })
    }

    private fun setStatus(visible: Boolean, text: String) {
        status.visibility = if (visible) View.VISIBLE else View.GONE
        status.text = text
    }
}

/** Bir podcast programının bölümleri. */
class PodcastShowPage(ui: CarUi, private val show: PodcastShow) : Page(ui) {
    override val title = show.name

    private var episodes: List<MediaEntry> = emptyList()
    private lateinit var adapter: EpisodeAdapter
    private lateinit var status: TextView
    private lateinit var subButton: FrameLayout

    override fun build(): View = with(ctx) {
        adapter = EpisodeAdapter()
        status = label("Bölümler yükleniyor…", 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }

        val cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            load(show.image) {
                placeholder(R.drawable.ic_podcast); error(R.drawable.ic_podcast)
                transformations(RoundedCornersTransformation(dpf(14)))
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(4), 0, dp(10), 0)
            addView(cover, lp(dp(120), dp(120)))
            addView(label(show.name, 16f, bold = true, lines = 3).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(10)))
            addView(label(show.author, 12f, Palette.TEXT_DIM, lines = 2).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(4)))
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            if (!ui.compact.value) addView(header, lp(dp(160), MATCH))
            addView(FrameLayout(this@with).apply {
                addView(verticalList(adapter), flp(MATCH, MATCH))
                addView(status, flp(MATCH, WRAP, Gravity.CENTER))
            }, lp(0, MATCH, 1f))
        }.also {
            scope.launch {
                try {
                    val feed = PodcastApi.feedUrl(show) ?: run {
                        status.text = "Bu program bölümlerini yalnızca Apple Podcasts'te yayınlıyor."
                        return@launch
                    }
                    val eps = PodcastApi.episodes(feed)
                    adapter.meta = eps.associate { it.url to (it.date + if (it.duration.isNotBlank()) "  •  ${it.duration}" else "") }
                    episodes = eps.map { MediaEntry(Kind.PODCAST, it.title, it.url, it.image ?: show.image, show.name) }
                    adapter.notifyDataSetChanged()
                    status.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
                    status.text = "Bu programda bölüm bulunamadı."
                } catch (e: Exception) {
                    status.text = "Bölümler alınamadı. İnternet bağlantını kontrol et."
                }
            }
            scope.launch {
                kotlinx.coroutines.flow.combine(PlayerHub.now, PlayerHub.playing) { _, _ -> }.collect { adapter.notifyDataSetChanged() }
            }
        }
    }

    override fun actions(): List<View> {
        subButton = ctx.circleButton(if (Prefs.isSubscribed(show.id)) R.drawable.ic_star else R.drawable.ic_star_border) {
            val now = Prefs.toggleSubscription(show)
            (subButton.getChildAt(0) as ImageView).setImageResource(if (now) R.drawable.ic_star else R.drawable.ic_star_border)
            ui.toast(if (now) "Abone olundu: ${show.name}" else "Abonelikten çıkıldı")
        }
        return listOf(subButton)
    }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeAdapter.VH>() {
        var meta: Map<String, String> = emptyMap()

        inner class VH(val root: LinearLayout, val title: TextView, val sub: TextView, val icon: ImageView) : RecyclerView.ViewHolder(root)

        override fun getItemCount() = episodes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = with(ctx) {
            val ic = icon(R.drawable.ic_play, 22)
            val t = label("", 15f, bold = true, lines = 2)
            val s = label("", 12f, Palette.TEXT_DIM)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                addView(FrameLayout(this@with).apply {
                    background = rounded(Palette.withAlpha(Palette.accent, 0x40), dpf(20))
                    addView(ic, flp(dp(22), dp(22), Gravity.CENTER))
                }, lp(dp(40), dp(40)).margins(r = dp(12)))
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(t)
                    addView(s, lp(MATCH, WRAP).margins(t = dp(4)))
                }, lp(0, WRAP, 1f))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).margins(b = dp(6))
            }
            VH(root, t, s, ic)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = episodes[position]
            val playing = PlayerHub.now.value?.url == e.url
            val r = ctx.dpf(14)
            h.root.background = pressable(
                if (playing) rounded(Palette.withAlpha(Palette.accent, 0x40), r, ctx.dp(2), Palette.accent) else rounded(Palette.SURFACE, r), r)
            h.root.neonGlow(playing, 8)
            h.title.text = e.title
            val pos = Prefs.position(e.url)
            val resume = if (pos > 60_000) "  •  Kaldığın yer: ${pos / 60_000} dk" else ""
            h.sub.text = (meta[e.url].orEmpty() + resume).trim().ifBlank { show.name }
            h.icon.setImageResource(if (playing && PlayerHub.playing.value) R.drawable.ic_pause else R.drawable.ic_play)
            h.root.onTap {
                if (playing) PlayerHub.toggle() else PlayerHub.play(episodes, position)
                notifyDataSetChanged()
            }
        }
    }
}
