// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.content.ContentUris
import android.database.Cursor
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
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
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalMediaPage(ui: CarUi) : Page(ui) {
    override val title = t("Telefon medya")

    private enum class Tab(val label: String) { MUSIC(t("Müzik")), VIDEO("Video") }

    private var tab = Tab.MUSIC
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    private var musicEntries = emptyList<MediaEntry>()
    private var videoEntries = emptyList<MediaEntry>()

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        status = label(t("Yükleniyor…"), 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        scope.launch {
            val (m, v) = withContext(Dispatchers.IO) { scan() }
            musicEntries = m
            videoEntries = v
            show()
        }

        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
                lp(MATCH, WRAP).margins(b = dp(8)))
            addView(FrameLayout(this@with).apply {
                addView(tileGrid(tiles, minTileDp = 140), flp(MATCH, MATCH))
                addView(status, flp(MATCH, WRAP, Gravity.CENTER).margins(dp(30), 0, dp(30), 0))
            }, lp(MATCH, 0, 1f))
        }
    }

    private fun show(): Unit = with(ctx) {
        tabs.removeAllViews()
        Tab.values().forEach { t ->
            val count = if (t == Tab.MUSIC) musicEntries.size else videoEntries.size
            tabs.addView(pill("${t.label} ($count)", selected = t == tab) { tab = t; show() },
                lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        val list = if (tab == Tab.MUSIC) musicEntries else videoEntries
        tiles.items = list.mapIndexed { i, e ->
            Tile(e.key, e.title, e.group, null, if (tab == Tab.MUSIC) R.drawable.ic_radio else R.drawable.ic_smart_display) {
                PlayerHub.play(list, i)
                if (tab == Tab.VIDEO) ui.push(PlayerPage(ui))
            }
        }
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = when {
            musicEntries.isEmpty() && videoEntries.isEmpty() -> t("Telefonda medya dosyası bulunamadı.\nMedya izni gerekebilir.")
            list.isEmpty() && tab == Tab.MUSIC -> t("Müzik dosyası bulunamadı.")
            list.isEmpty() && tab == Tab.VIDEO -> t("Video dosyası bulunamadı.")
            else -> ""
        }
    }

    private data class ScanResult(val music: List<MediaEntry>, val video: List<MediaEntry>)

    private fun scan(): ScanResult {
        val music = mutableListOf<MediaEntry>()
        val video = mutableListOf<MediaEntry>()

        queryAudio()?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = c.getString(titleCol) ?: t("Bilinmeyen")
                val artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: ""
                val dur = c.getLong(durationCol)
                if (dur < 10_000) continue // 10 sn'den kısa ses dosyalarını atla (bildirim sesleri vs.)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                music += MediaEntry(Kind.PODCAST, title, uri, null, artist)
            }
        }

        queryVideo()?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val folderCol = c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val title = c.getString(titleCol) ?: t("Bilinmeyen")
                val dur = c.getLong(durationCol)
                if (dur < 3_000) continue
                val folder = if (folderCol >= 0) c.getString(folderCol)?.substringAfterLast('/')?.trimEnd('/') ?: "" else ""
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                video += MediaEntry(Kind.PODCAST, title, uri, null, folder)
            }
        }

        return ScanResult(music.sortedBy { it.title.lowercase() }, video.sortedBy { it.title.lowercase() })
    }

    private fun queryAudio(): Cursor? = try {
        ctx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.DURATION),
            "${MediaStore.Audio.Media.IS_MUSIC} = 1",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )
    } catch (_: Exception) { null }

    private fun queryVideo(): Cursor? = try {
        ctx.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.TITLE, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.RELATIVE_PATH),
            null, null,
            "${MediaStore.Video.Media.TITLE} ASC"
        )
    } catch (_: Exception) { null }
}
