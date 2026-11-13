// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Mikrofon açılınca çalan yumuşak "dinliyorum" sesi: iki sıcak nota (aşağıdan yukarı, lo-fi / chill tınısı),
 * hafif yankıyla söner. Kodla üretilir, dosya yoktur. Çalan müziği kısmaz, çok kısa sürer.
 */
object Chime {
    private const val RATE = 44100

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** Dinlemeye başlarken (yükselen) ya da bitince (alçalan) çalınır; süresini (ms) döndürür. */
    fun play(rising: Boolean = true): Long {
        // Dmaj7 tınısından iki nota: F#5 → A5 (yükselen), A5 → D5 (alçalan)
        val notes = if (rising) listOf(739.99 to 0.0, 880.0 to 0.11) else listOf(880.0 to 0.0, 587.33 to 0.10)
        val total = 0.75
        val n = (RATE * total).toInt()
        val buf = FloatArray(n)
        for ((freq, start) in notes) {
            val s0 = (start * RATE).toInt()
            for (i in s0 until n) {
                val t = (i - s0).toDouble() / RATE
                // Yumuşak giriş (15 ms), uzun sönüm; temel + oktav + hafif beşli (Rhodes/marimba arası)
                val env = (t / 0.015).coerceAtMost(1.0) * exp(-t * 5.5)
                val v = sin(2 * PI * freq * t) + 0.22 * sin(2 * PI * freq * 2 * t) * exp(-t * 9) +
                    0.08 * sin(2 * PI * freq * 3 * t) * exp(-t * 14)
                buf[i] += (v * env * 0.22).toFloat()
            }
        }
        // Hafif yankı: 90 ve 180 ms gecikmeli, kısık kopyalar (ses "havada" kalsın)
        for (i in n - 1 downTo 0) {
            val d1 = i - (0.09 * RATE).toInt()
            val d2 = i - (0.18 * RATE).toInt()
            if (d1 >= 0) buf[i] += buf[d1] * 0.28f
            if (d2 >= 0) buf[i] += buf[d2] * 0.12f
        }
        val pcm = ShortArray(n) { (buf[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        runCatching {
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, (total * 1000).toLong() + 200)
        }
        return (total * 1000).toLong()
    }
}
