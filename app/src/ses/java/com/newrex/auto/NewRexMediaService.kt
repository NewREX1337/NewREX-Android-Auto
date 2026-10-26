// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.newrex.auto.data.FmPresets
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Playlists
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * NewREX Ses: arabanın müzik ekranında (Spotify gibi) görünür. Sadece ses çalar, bu yüzden Android Auto
 * sürüşte de açık tutar ve Play Store dışı kurulumda "Bilinmeyen kaynaklar" ile kabul eder.
 */
class NewRexMediaService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null

    /** Son gösterilen listeler: seçilen öğe kendi listesiyle çalınsın (ileri/geri o listede gezsin). */
    private val lists = HashMap<String, List<MediaEntry>>()

    private val folders = listOf(
        FAV to "⭐ Favoriler",
        RECENT to "🕘 Son çalınanlar",
        FM to "📻 FM istasyonları",
        RADIO to "📻 Popüler radyolar",
        TV to "📺 TV (sadece ses)",
    )

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        FmPresets.init(this)
        PlayerHub.externalSession = true
        PlayerHub.init(applicationContext)
        PlayerHub.resolver = { id -> find(id) }
        session = MediaLibrarySession.Builder(this, PlayerHub.forSession, Callback()).setId("newrex-ses").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        PlayerHub.resolver = null
        PlayerHub.release()
        super.onDestroy()
    }

    private fun find(id: String): Pair<List<MediaEntry>, Int>? {
        for (l in lists.values) {
            val i = l.indexOfFirst { it.key == id }
            if (i >= 0) return l to i
        }
        return null
    }

    private suspend fun load(id: String): List<MediaEntry> = when (id) {
        FAV -> Prefs.favorites()
        RECENT -> Prefs.recents()
        FM -> coroutineScope {
            FmPresets.all().map { p ->
                async { runCatching { FmPresets.resolve(p) }.getOrNull()?.let { it.copy(title = "${FmPresets.format(p.freq)}  ${it.title}") } }
            }.awaitAll().filterNotNull()
        }
        RADIO -> RadioApi.popular()
        TV -> Playlists.loadAll(this).channels
        else -> emptyList()
    }

    private fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()
    ).build()

    private fun item(e: MediaEntry) = MediaItem.Builder().setMediaId(e.key).setMediaMetadata(
        MediaMetadata.Builder().setTitle(e.title).setArtist(e.group.ifBlank { null })
            .setIsBrowsable(false).setIsPlayable(true)
            .apply { e.image?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
    ).build()

    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "NewREX"), params))

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            if (parentId == ROOT) {
                return Futures.immediateFuture(LibraryResult.ofItemList(folders.map { folder(it.first, it.second) }, params))
            }
            val out = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val all = runCatching { load(parentId) }.getOrDefault(emptyList()).distinctBy { it.key }
                lists[parentId] = all
                val from = (page * pageSize).coerceIn(0, all.size)
                val to = (from + pageSize).coerceIn(from, all.size)
                out.set(LibraryResult.ofItemList(all.subList(from, to).map(::item), params))
            }
            return out
        }

        override fun onGetItem(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val hit = find(mediaId) ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item(hit.first[hit.second]), null))
        }

        // Öğelerin adresi yok; PlayerHub mediaId'den kanalı bulup kendisi çalar
        override fun onAddMediaItems(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems)

        // Araç "çal" deyince son dinlenen başlasın
        override fun onPlaybackResumption(
            mediaSession: MediaSession, controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val recent = Prefs.recents()
            lists[RECENT] = recent
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(recent.take(1).map(::item), 0, 0))
        }
    }

    private companion object {
        const val ROOT = "root"
        const val FAV = "fav"
        const val RECENT = "recent"
        const val FM = "fm"
        const val RADIO = "radio"
        const val TV = "tv"
    }
}
