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
import com.newrex.auto.data.CarLog
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Hız uyarısının sesi. Navigasyon uygulamalarının yön tarifi gibi çalınır: Android Auto bu sesi arabanın
 * hoparlöründen verir ve çalan radyoyu o sırada kısar. (Bildirim sesi arabaya gitmiyordu, telefon sessizdeyse
 * hiç duyulmuyordu.) Sesler araçların kendi uyarı tonları gibi: alçak, yumuşak başlayıp sönen notalar.
 */
object SpeedAlert {
    /** Seçilebilir sesler (Araç → Hız uyarısı). Masaüstündeki örnek dosyalarla aynı formüller. */
    val SOUNDS = listOf("Nazik", "Ding dong", "Üçlü", "Çan", "Uyarı")

    private const val RATE = 44100

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val handler = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null

    /** Uyarıyı çalar. */
    fun play(context: Context) = play(context, CarLog.speedSound)

    /** Ayarlarda sese dokununca dinletmek için de kullanılır. */
    fun play(context: Context, sound: Int) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = req
        am.requestAudioFocus(req)
        val ms = playPcm(samples(sound))
        handler.postDelayed({ if (focus === req) { am.abandonAudioFocusRequest(req); focus = null } }, ms + 200L)
    }

    // ---- Ses üretimi ----

    /** Yumuşak, sıcak tını: temel nota + hafif üst sesler (marimba / araç gong'u gibi). */
    private val WARM = listOf(1.0 to 1.0, 2.0 to 0.18, 3.0 to 0.06)

    /** Çan tınısı: biraz daha zengin, uzun sönen. */
    private val BELL = listOf(1.0 to 1.0, 2.0 to 0.35, 2.76 to 0.12, 5.4 to 0.04)

    /**
     * Tek nota: 12 ms'de yumuşakça yükselir, [decay] saniyelik üstel sönümle biter (sert başlayıp kesilmez).
     * [at] ms'den başlayarak [out] üzerine eklenir; notalar birbirinin üstüne binebilir.
     */
    private fun note(out: FloatArray, at: Int, freq: Double, ms: Int, decay: Double, vol: Double = 0.5,
                     harmonics: List<Pair<Double, Double>> = WARM) {
        val start = RATE * at / 1000
        val n = RATE * ms / 1000
        val attack = RATE * 0.012
        val total = harmonics.sumOf { it.second }
        for (i in 0 until n) {
            val k = start + i
            if (k >= out.size) break
            val env = minOf(1.0, i / attack) * exp(-i / (RATE * decay)) * minOf(1.0, (n - i) / (RATE * 0.02))
            val v = harmonics.sumOf { (h, a) -> a * sin(2 * PI * freq * h * i / RATE) } / total
            out[k] += (v * env * vol).toFloat()
        }
    }

    private fun samples(sound: Int): FloatArray = when (sound) {
        // Ding dong: Mi → Do, kapı zili gibi
        1 -> FloatArray(RATE * 1500 / 1000).also { note(it, 0, 659.3, 900, 0.35); note(it, 380, 523.3, 1100, 0.45) }
        // Üçlü: Do-Mi-Sol yükselen akor
        2 -> FloatArray(RATE * 1300 / 1000).also {
            note(it, 0, 523.3, 600, 0.22); note(it, 160, 659.3, 600, 0.22); note(it, 320, 784.0, 950, 0.35)
        }
        // Çan: alçak La, uzun sönen
        3 -> FloatArray(RATE * 1800 / 1000).also { note(it, 0, 440.0, 1800, 0.6, vol = 0.55, harmonics = BELL) }
        // Uyarı: üç yumuşak vuruş (Re), dikkat çekici ama tiz değil
        4 -> FloatArray(RATE * 1000 / 1000).also { for (j in 0..2) note(it, j * 260, 587.3, 240, 0.09, vol = 0.55) }
        // Nazik: aynı notadan iki kısa yumuşak ton
        else -> FloatArray(RATE * 900 / 1000).also { note(it, 0, 587.3, 380, 0.14); note(it, 300, 587.3, 600, 0.2) }
    }

    /** Sesi çalar, süresini (ms) döner. */
    private fun playPcm(samples: FloatArray): Long = runCatching {
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
