// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.newrex.auto.data.CarLog
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hız uyarısının sesi. Navigasyon uygulamalarının yön tarifi gibi çalınır: Android Auto bu sesi arabanın
 * hoparlöründen verir ve çalan radyoyu o sırada kısar. (Bildirim sesi arabaya gitmiyordu, telefon sessizdeyse
 * hiç duyulmuyordu.) Önce seçilen uyarı sesi, sonra istenirse Türkçe "Hız sınırı, 95".
 */
object SpeedAlert {
    /** Seçilebilir sesler (Araç → Hız uyarısı). Masaüstündeki örnek dosyalarla aynı formüller. */
    val SOUNDS = listOf("Bip", "Ding dong", "Üçlü", "Çan", "Acil")

    /**
     * Konuşan sesler: telefonun Google metin okuma motorundaki Türkçe sesler (telefonun içinde çalışır, internete
     * bir şey gönderilmez). Telefonda o ses yoksa telefonun varsayılan Türkçe sesi kullanılır.
     */
    val VOICES = listOf("Kapalı" to null, "Kadın 1" to "tr-tr-x-cfs-local", "Kadın 2" to "tr-tr-x-efu-local",
        "Kadın 3" to "tr-tr-x-mfm-local", "Erkek 1" to "tr-tr-x-tmc-local", "Erkek 2" to "tr-tr-x-ama-local")

    private const val RATE = 22050

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: AudioFocusRequest? = null

    fun init(context: Context) {
        if (tts != null) return
        val app = context.applicationContext
        tts = TextToSpeech(app) { status ->
            val t = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            ttsReady = t.setLanguage(Locale("tr", "TR")) >= TextToSpeech.LANG_AVAILABLE
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { handler.post { release(app) } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) { handler.post { release(app) } }
            })
        }
    }

    /** Uyarıyı çalar. Türkçe ses yoksa ya da konuşma kapalıysa sadece uyarı sesi çalar. */
    fun play(context: Context, kmh: Int) = play(context, CarLog.speedSound, CarLog.speedVoice, "Dikkat! Hız sınırını aştın. $kmh.")

    /** Ayarlarda sese dokununca dinletmek için. */
    fun preview(context: Context, sound: Int) = play(context, sound, 0, null)

    /** Ayarlarda konuşan sese dokununca dinletmek için. */
    fun previewVoice(context: Context, voice: Int) = play(context, -1, voice, "Dikkat! Hız sınırını aştın.")

    private fun play(context: Context, sound: Int, voice: Int, speech: String?) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = req
        am.requestAudioFocus(req)
        val ms = if (sound >= 0) playPcm(samples(sound)) else 0L
        handler.postDelayed({
            val t = tts
            if (speech != null && voice > 0 && t != null && ttsReady) {
                val name = VOICES.getOrNull(voice)?.second
                t.voices.orEmpty().firstOrNull { it.name == name }?.let { t.voice = it } ?: t.setLanguage(Locale("tr", "TR"))
                t.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "speed")
            } else release(app)
        }, ms + 150L)
    }

    private fun release(app: Context) {
        val req = focus ?: return
        focus = null
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(req)
    }

    // ---- Ses üretimi ----

    private val BELL = listOf(1.0 to 1.0, 2.0 to 0.5, 3.0 to 0.25, 4.2 to 0.12)

    private fun tone(out: ArrayList<Float>, freq: Double, ms: Int, vol: Double = 0.8, decay: Double? = null,
                     harmonics: List<Pair<Double, Double>> = listOf(1.0 to 1.0), fade: Double = 300.0) {
        val n = RATE * ms / 1000
        val total = harmonics.sumOf { it.second }
        for (i in 0 until n) {
            // Başı ve sonu yumuşak: çıt sesi olmasın
            var env = minOf(1.0, i / fade, (n - i) / fade)
            if (decay != null) env *= exp(-i / (RATE * decay))
            val v = harmonics.sumOf { (h, a) -> a * sin(2 * PI * freq * h * i / RATE) } / total
            out += (v * env * vol).toFloat()
        }
    }

    private fun silence(out: ArrayList<Float>, ms: Int) = repeat(RATE * ms / 1000) { out += 0f }

    private fun samples(sound: Int): ArrayList<Float> {
        val s = ArrayList<Float>()
        when (sound) {
            1 -> { tone(s, 1319.0, 450, decay = 0.18, harmonics = BELL); tone(s, 1047.0, 700, decay = 0.25, harmonics = BELL) }
            2 -> { tone(s, 660.0, 130); silence(s, 70); tone(s, 880.0, 130); silence(s, 70); tone(s, 1175.0, 220) }
            3 -> tone(s, 1047.0, 1100, vol = 0.7, decay = 0.35, harmonics = BELL, fade = 150.0)
            4 -> repeat(5) { tone(s, 1000.0, 90); silence(s, 60) }
            else -> { tone(s, 880.0, 180); silence(s, 120); tone(s, 880.0, 180) }
        }
        return s
    }

    /** Sesi çalar, süresini (ms) döner. */
    private fun playPcm(samples: List<Float>): Long = runCatching {
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        val ms = pcm.size * 1000L / RATE
        handler.postDelayed({ runCatching { track.stop(); track.release() } }, ms + 200)
        ms
    }.getOrDefault(0L)
}
