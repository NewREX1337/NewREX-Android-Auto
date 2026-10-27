// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.MediaSession
import com.newrex.auto.data.Kind
import com.newrex.auto.data.FmPresets
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Net
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import com.newrex.auto.data.normName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Tek bir oynatıcı: TV, radyo ve podcast aynı oynatıcıyı paylaşır, sayfadan çıkınca ses devam eder. */
object PlayerHub {
    // Bazı IPTV sunucuları tarayıcı dışı istemcileri reddediyor
    private const val STREAM_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    // Radyolar ise tarayıcıya yayın değil web sayfası gönderiyor ya da birkaç saniye sonra kesiyor
    // (StreamTheWorld: Joy Türk, Süper FM, Metro FM, Virgin; Shoutcast sunucuları). Oynatıcı gibi tanıtılır.
    private const val AUDIO_USER_AGENT = Net.USER_AGENT

    /** Kopan canlı yayına kaç kez yeniden bağlanılır (1, 2, 4, 8, sonra 15 sn arayla; tünelde ~2 dk bekler). */
    private const val MAX_RETRIES = 10

    private lateinit var tvSources: DefaultMediaSourceFactory
    private lateinit var audioSources: DefaultMediaSourceFactory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var retries = 0

    /** Bu yayın en az bir kez çaldı mı: çalmış yayın kopunca uzun beklenir, hiç açılmayan için kısa. */
    private var playedOk = false
    private var healJob: Job? = null

    /** Bu istasyon için denenmiş ve çalmayan adresler. */
    private val dead = HashSet<String>()
    private val reconnect = Runnable { reload() }

    /** 30 sn sorunsuz çalan yayında yeniden bağlanma hakkı yenilenir. */
    private val stable = Runnable { retries = 0 }

    private var exo: ExoPlayer? = null
    private var session: MediaSession? = null
    private var sessionPlayer: Player? = null
    private var appContext: Context? = null
    private var queue: List<MediaEntry> = emptyList()
    private var index = 0

    val now = MutableStateFlow<MediaEntry?>(null)
    val playing = MutableStateFlow(false)
    val buffering = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** Podcast'lerde ilerleme (ms). */
    val position = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)

    private val handler = Handler(Looper.getMainLooper())
    private var ticks = 0
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val e = now.value
            if (e?.kind == Kind.PODCAST) {
                position.value = p.currentPosition
                duration.value = p.duration.coerceAtLeast(0)
                // Kalınan yer 10 saniyede bir kaydedilir (her saniye diske yazmak gereksiz yük)
                if (p.isPlaying && ++ticks % 10 == 0) Prefs.savePosition(e.url, p.currentPosition)
            }
            handler.postDelayed(this, 1000)
        }
    }

    val player: ExoPlayer get() = exo ?: error("PlayerHub.init çağrılmadı")

    fun init(context: Context) {
        if (exo != null) return
        fun sources(userAgent: String) = DefaultMediaSourceFactory(DefaultDataSource.Factory(context,
            TlsFallbackFactory(DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000))))
            // Mobil veride kısa kopmalarda hemen hata vermesin
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        tvSources = sources(STREAM_USER_AGENT)
        audioSources = sources(AUDIO_USER_AGENT)
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(audioSources)
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
                if (state == Player.STATE_READY) { error.value = null; playedOk = true; handler.postDelayed(stable, 30_000) }
                else handler.removeCallbacks(stable)
                if (state == Player.STATE_ENDED) {
                    val e = now.value
                    // Biten bölüm bir dahaki sefere baştan başlasın
                    if (e?.kind == Kind.PODCAST) Prefs.savePosition(e.url, 0)
                    // Canlı yayın "bitmez": sunucu bağlantıyı kapattıysa yeniden bağlan
                    else if (e != null) retry()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                val cur = now.value
                // Adres ölmüşse (404, 501…) beklemeden yedek yayına geç; diğer hatalarda önce yeniden bağlan
                // (bozuk yayın listesi, çözülemeyen ses gibi 3000+ hatalar da tekrar denemekle düzelmez)
                val gone = e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    e.errorCode >= PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (cur?.kind == Kind.RADIO && gone && heal(cur)) return
                if (cur != null && cur.kind != Kind.PODCAST && retry()) return
                // İnternet yokken yedek yayın aramak boşuna
                val offline = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                if (cur?.kind == Kind.RADIO && !offline && heal(cur)) return
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
        applyQuality()
        this.sessionPlayer = sessionPlayer
        appContext = context.applicationContext
        handler.post(ticker)
    }

    fun play(list: List<MediaEntry>, startIndex: Int) {
        if (list.isEmpty()) return
        queue = list
        index = startIndex.coerceIn(0, list.lastIndex)
        start()
    }

    val isPodcast get() = now.value?.kind == Kind.PODCAST

    fun seekBy(ms: Long) {
        val p = exo ?: return
        val d = p.duration
        val target = (p.currentPosition + ms).coerceAtLeast(0)
        p.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
        position.value = p.currentPosition
    }

    fun seekTo(ms: Long) {
        exo?.seekTo(ms.coerceAtLeast(0))
        position.value = ms
    }

    /** Podcast'te direksiyon ve mini oynatıcı tuşları 30 sn ileri / 15 sn geri sarar. */
    fun next() {
        if (isPodcast) { seekBy(30_000); return }
        if (queue.isEmpty()) return
        index = (index + 1) % queue.size
        start()
    }

    fun prev() {
        if (isPodcast) { seekBy(-15_000); return }
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
        cancelRecovery()
        now.value?.takeIf { it.kind == Kind.PODCAST }?.let { e -> exo?.let { Prefs.savePosition(e.url, it.currentPosition) } }
        exo?.stop()
        exo?.clearMediaItems()
        now.value = null
        error.value = null
        // Biz çalmıyorken direksiyon tuşları ve bildirim diğer uygulamada (Spotify vb.) kalsın
        session?.release()
        session = null
    }

    fun release() {
        cancelRecovery()
        now.value?.takeIf { it.kind == Kind.PODCAST }?.let { e -> exo?.let { Prefs.savePosition(e.url, it.currentPosition) } }
        handler.removeCallbacks(ticker)
        session?.release()
        exo?.release()
        session = null
        exo = null
        now.value = null
        playing.value = false
    }

    /** TV görüntü kalitesi seçimi (Prefs.tvQuality): Otomatik en iyisini seçer, diğerleri üst sınırdır. */
    val QUALITIES = listOf("Otomatik" to 0, "1080p" to 1080, "720p" to 720, "480p" to 480)

    fun applyQuality() {
        val p = exo ?: return
        val h = QUALITIES.getOrNull(Prefs.tvQuality)?.second ?: 0
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .apply { if (h > 0) setMaxVideoSize(h * 16 / 9 + 1, h) else clearVideoSizeConstraints() }
            .build()
    }

    /** Kopan canlı yayına artan aralıklarla yeniden bağlanır. Bir süre düzgün çaldıysa sayaç sıfırlanır. */
    private fun retry(): Boolean {
        if (retries >= (if (playedOk) MAX_RETRIES else 2)) return false
        buffering.value = true
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, (1000L shl retries.coerceAtMost(4)).coerceAtMost(15_000))
        retries++
        return true
    }

    /** Yayın tamamen ölmüşse aynı adlı istasyonun başka bir yayın adresine geçer. */
    private fun heal(e: MediaEntry): Boolean {
        if (healJob?.isActive == true) return true
        dead += e.url
        val name = e.title.normName()
        if (name.isEmpty()) return false
        buffering.value = true
        healJob = scope.launch {
            val alt = runCatching { RadioApi.search(e.title, country = "TR", limit = 20) }.getOrNull().orEmpty()
                .filter { it.url !in dead }
                .firstOrNull { val n = it.title.normName(); n == name || n.startsWith("$name ") }
            if (now.value?.key != e.key) return@launch
            if (alt == null) {
                buffering.value = false
                error.value = "Yayın açılamadı. Başka bir kanal/istasyon deneyin."
                return@launch
            }
            val fixed = e.copy(url = alt.url, image = e.image ?: alt.image, home = e.home ?: alt.home)
            FmPresets.heal(e.url, fixed)
            if (index in queue.indices && queue[index].key == e.key) queue = queue.toMutableList().also { it[index] = fixed }
            retries = 0
            playedOk = false
            now.value = fixed
            reload()
        }
        return true
    }

    private fun cancelRecovery() {
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(stable)
        healJob?.cancel()
        retries = 0
    }

    private fun itemFor(e: MediaEntry) = MediaItem.Builder()
        .setMediaId(e.key)
        .setUri(e.url)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(e.title)
                .setArtist(when (e.kind) {
                    Kind.TV -> "NewREX TV"
                    Kind.RADIO -> "NewREX Radyo"
                    Kind.PODCAST -> e.group.ifBlank { "NewREX Podcast" }
                })
                .build()
        )
        .apply { if (e.url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .build()

    private fun sourceFor(e: MediaEntry) = (if (e.kind == Kind.TV) tvSources else audioSources).createMediaSource(itemFor(e))

    /** Aynı yayını baştan açar (yönlendirmeli adresler yeni sunucuya bağlanır). */
    private fun reload() {
        val p = exo ?: return
        val e = now.value ?: return
        p.setMediaSource(sourceFor(e))
        p.prepare()
        p.play()
    }

    private fun start() {
        cancelRecovery()
        dead.clear()
        playedOk = false
        now.value?.takeIf { it.kind == Kind.PODCAST }?.let { e -> exo?.let { Prefs.savePosition(e.url, it.currentPosition) } }
        val e = queue[index]
        now.value = e
        error.value = null
        Prefs.addRecent(e)
        if (e.kind == Kind.PODCAST) player.setMediaSource(sourceFor(e), Prefs.position(e.url)) else player.setMediaSource(sourceFor(e))
        position.value = 0
        duration.value = 0
        // Medya oturumu sadece biz bir şey çalınca açılır; yoksa açılışta Spotify'ın tuşlarını üstleniyorduk
        if (session == null) {
            val c = appContext
            val sp = sessionPlayer
            if (c != null && sp != null) session = MediaSession.Builder(c, sp).setId("newrex").build()
        }
        player.prepare()
        player.play()
    }
}
