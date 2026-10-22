// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

/** Tek bir oynatıcı: TV ve radyo aynı oynatıcıyı paylaşır, sayfadan çıkınca ses devam eder. */
object PlayerHub {
    // Bazı IPTV sunucuları tarayıcı dışı istemcileri reddediyor
    private const val STREAM_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var queue: List<MediaEntry> = emptyList()
    private var index = 0

    val now = MutableStateFlow<MediaEntry?>(null)
    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val player: ExoPlayer get() = exo ?: error("PlayerHub.init çağrılmadı")

    fun init(context: Context) {
        if (exo != null) return
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(STREAM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing.value = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering.value = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) error.value = null
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                buffering.value = false
                error.value = "Yayın açılamadı. Başka bir kanal/istasyon deneyin."
            }
        })

        // Direksiyondaki ileri/geri tuşları listede bir sonraki kanala/istasyona geçsin
        val sessionPlayer = object : ForwardingPlayer(p) {
            private val extra = intArrayOf(
                COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            )

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(*extra).build()

            override fun isCommandAvailable(command: Int) =
                command in extra || super.isCommandAvailable(command)

            override fun seekToNext() = next()
            override fun seekToNextMediaItem() = next()
            override fun seekToPrevious() = prev()
            override fun seekToPreviousMediaItem() = prev()
        }
        exo = p
        session = MediaSession.Builder(context, sessionPlayer).setId("newrex").build()
    }

    fun play(list: List<MediaEntry>, startIndex: Int) {
        if (list.isEmpty()) return
        queue = list
        index = startIndex.coerceIn(0, list.lastIndex)
        start()
    }

    fun next() {
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        start()
    }

    fun prev() {
        if (queue.isEmpty()) return
        index = (index - 1 + queue.size) % queue.size
        start()
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
            // Canlı yayında duraklatınca geride kalmamak için canlıya dön
            if (p.isCurrentMediaItemLive) p.seekToDefaultPosition()
            p.play()
        }
    }

    fun stop() {
        exo?.stop()
        exo?.clearMediaItems()
        now.value = null
        error.value = null
    }

    fun release() {
        session?.release()
        exo?.release()
        session = null
        exo = null
        now.value = null
        playing.value = false
    }

    private fun start() {
        val e = queue[index]
        now.value = e
        error.value = null
        Prefs.addRecent(e)
        val item = MediaItem.Builder()
            .setUri(e.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(if (e.kind == Kind.TV) "NewREX TV" else "NewREX Radyo")
                    .build()
            )
            .apply { if (e.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }
}
