// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import com.newrex.auto.data.FmPresets
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Logos
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Sadece debug sürümünde: radyo oynatmayı araç ekranı olmadan dener.
 * adb shell am broadcast -n com.newrex.auto/.DebugRadio -a play --es url URL --es title AD
 * adb shell am broadcast -n com.newrex.auto/.DebugRadio -a fm --es title "Joy Türk"
 * adb shell am broadcast -n com.newrex.auto/.DebugRadio -a logos
 */
class DebugRadio : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.init(context)
        FmPresets.init(context)
        PlayerHub.init(context.applicationContext)
        if (!hooked) {
            hooked = true
            PlayerHub.player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) = log("durum=" + listOf("?", "BOŞ", "YÜKLENİYOR", "HAZIR", "BİTTİ")[state])
                override fun onIsPlayingChanged(isPlaying: Boolean) = log("çalıyor=$isPlaying")
                override fun onPlayerError(error: PlaybackException) = log("hata=${error.errorCodeName} ${error.cause?.message}")
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    tracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }.forEach { g ->
                        for (i in 0 until g.length) if (g.isTrackSelected(i)) log("seçilen görüntü: ${g.getTrackFormat(i).height}p")
                    }
                }
            })
            scope.launch { PlayerHub.now.collect { log("şimdi=${it?.title} ${it?.url}") } }
            scope.launch { PlayerHub.error.collect { if (it != null) log("ekrandaki hata=$it") } }
        }
        when (intent.action) {
            "play" -> PlayerHub.play(listOf(MediaEntry(if (intent.getStringExtra("kind") == "tv") Kind.TV else Kind.RADIO, intent.getStringExtra("title") ?: "Test",
                intent.getStringExtra("url") ?: return, null, "")), 0)
            "fm" -> scope.launch {
                val t = intent.getStringExtra("title") ?: return@launch
                val p = FmPresets.all().firstOrNull { it.title == t } ?: return@launch log("frekans yok: $t")
                val e = FmPresets.resolve(p) ?: return@launch log("çözülemedi: $t")
                PlayerHub.play(listOf(e), 0)
            }
            "stop" -> PlayerHub.stop()
            "voice" -> {
                // Telefonun Türkçe sesiyle "Hız sınırı. 95" kaydı: /sdcard/Android/data/com.newrex.auto/files/hiz.wav
                val app = context.applicationContext
                var t: android.speech.tts.TextToSpeech? = null
                t = android.speech.tts.TextToSpeech(app) { st ->
                    val tts = t ?: return@TextToSpeech
                    val r = tts.setLanguage(java.util.Locale("tr", "TR"))
                    val f = java.io.File(app.getExternalFilesDir(null), "hiz.wav")
                    log("tts durum=$st dil=$r")
                    tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) = log("ses kaydı hazır: ${f.length()}")
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) = log("ses kaydı hatası")
                    })
                    tts.synthesizeToFile("Hız sınırı. 95", null, f, "v")
                }
            }
            "voices" -> {
                // Türkçe sesleri listeler ve her birini dosyaya kaydeder: files/v_<ad>.wav
                val app = context.applicationContext
                var t: android.speech.tts.TextToSpeech? = null
                t = android.speech.tts.TextToSpeech(app) { _ ->
                    val tts = t ?: return@TextToSpeech
                    val text = intent.getStringExtra("text") ?: "Dikkat! Hız sınırını aştın. Doksan beş."
                    val list = tts.voices.orEmpty().filter { it.locale.language == "tr" }.sortedBy { it.name }
                    list.forEach { v -> log("ses: ${v.name} ağ=${v.isNetworkConnectionRequired} kalite=${v.quality} kurulu=${!v.features.contains("notInstalled")}") }
                    tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) = Unit
                        override fun onDone(id: String?) = log("kayıt bitti: $id")
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) = log("kayıt hatası: $id")
                    })
                    list.filter { !it.isNetworkConnectionRequired }.forEach { v ->
                        tts.voice = v
                        tts.synthesizeToFile(text, null, java.io.File(app.getExternalFilesDir(null), "v_${v.name}.wav"), v.name)
                    }
                }
            }
            "saver" -> Prefs.saverMode = intent.getIntExtra("m", 0)
            "kmh" -> DriveState.speedKmh.value = intent.getIntExtra("v", 0).toFloat()
            "speed" -> {
                SpeedAlert.init(context)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ SpeedAlert.play(context, 95); log("hız uyarısı çalındı") }, 3000)
            }
            "logos" -> scope.launch {
                val list = RadioApi.popular()
                val before = list.count { Logos.best(it) != null }
                var found = 0
                Logos.check(list) { e, logo -> if (logo != null) found++; log("logo: ${e.title} -> $logo") }
                val after = list.count { Logos.best(it) != null }
                log("LOGO ÖZET: istasyon=${list.size} önce=$before sonra=$after yeni=$found")
            }
        }
    }

    private fun log(s: String) { Log.i("NRTEST", s) }

    private companion object {
        var hooked = false
        val scope = CoroutineScope(Dispatchers.Main)
    }
}
