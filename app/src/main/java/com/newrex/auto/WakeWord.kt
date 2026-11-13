// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.content.ContextCompat
import com.newrex.auto.data.Prefs

/**
 * "Hey NewREX" gibi uyandırma sözü. Araç ekranı açıkken arka planda telefonun kendi (mümkünse internetsiz)
 * ses tanıması dinler; "Hey" + kullanıcının seçtiği kelime duyulunca "Sizi dinliyorum" der ve komutu bekler.
 * Söz aynı cümlede devam ederse ("hey newrex hava durumu") komut hemen çalışır. Ses kaydedilmez, hiçbir yere
 * gönderilmez; telefon internetsiz tanımayı destekliyorsa tanıma da telefonda kalır.
 */
object WakeWord {
    private const val TAG = "NewREX"
    private val handler = Handler(Looper.getMainLooper())

    private var app: Context? = null
    private var onWake: ((String) -> Unit)? = null
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var paused = false
    private var failures = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** "Hey"in ardından gelen kelime (ör. "newrex", "maleren"). */
    val word: String get() = Prefs.wakeWord.ifBlank { "newrex" }

    fun start(context: Context, onWake: (rest: String) -> Unit) {
        app = context.applicationContext
        this.onWake = onWake
        active = Prefs.wakeEnabled
        if (!active) { stopRecognizer(); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        if (tts == null) tts = TextToSpeech(context.applicationContext) { ok ->
            ttsReady = ok == TextToSpeech.SUCCESS
            if (ttsReady) tts?.language = I18n.locale()
        }
        paused = false
        listenSoon(300)
    }

    fun stop() {
        active = false
        onWake = null
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }

    /** Komut dinlenirken mikrofon boşalsın. */
    fun pause() {
        paused = true
        handler.removeCallbacksAndMessages(null)
        stopRecognizer()
    }

    fun resume() {
        if (!active) return
        paused = false
        listenSoon(800)
    }

    /** Ayar değişince (açık/kapalı, kelime) yeniden başlar. */
    fun settingsChanged() {
        val ctx = app ?: return
        val cb = onWake ?: return
        stopRecognizer()
        start(ctx, cb)
    }

    private fun listenSoon(ms: Long) {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ listen() }, ms)
    }

    private fun stopRecognizer() {
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun listen() {
        val ctx = app ?: return
        if (!active || paused || VoiceCommand.listening.value) return
        stopRecognizer()
        // İnternetsiz tanıma varsa o kullanılır (ses telefondan çıkmaz, bip sesi de çıkmaz)
        val sr = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partial: Bundle?) {
                val texts = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                // Ara sonuçta sadece uyandırma sözünün kendisi duyulduysa hemen tepki ver
                for (t in texts) match(t)?.let { rest -> if (rest.isBlank()) { woke(sr, ""); return } }
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== sr) return
                failures = 0
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                for (t in texts) match(t)?.let { rest -> woke(sr, rest); return }
                again(sr, 250)
            }

            override fun onError(error: Int) {
                if (recognizer !== sr) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { stopRecognizer(); return }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> { failures = 0; again(sr, 200) }
                    // Meşgul/ağ hatalarında art arda denenip telefon yorulmasın: giderek uzayan bekleme
                    else -> { failures++; again(sr, (1000L shl failures.coerceAtMost(5)).coerceAtMost(30_000)) }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, I18n.speechTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { sr.startListening(intent) }.onFailure { Log.w(TAG, "Uyandırma dinlemesi başlamadı", it); again(sr, 5000) }
    }

    private fun again(sr: SpeechRecognizer, ms: Long) {
        if (recognizer === sr) stopRecognizer()
        if (active && !paused) listenSoon(ms)
    }

    private fun woke(sr: SpeechRecognizer, rest: String) {
        if (recognizer === sr) stopRecognizer()
        paused = true
        handler.removeCallbacksAndMessages(null)
        val cb = onWake ?: return
        if (rest.isNotBlank()) { cb(rest); return }
        // "Sizi dinliyorum" deyip ardından komutu dinle
        val reply = t("Sizi dinliyorum")
        val t = tts
        if (t != null && ttsReady) {
            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { handler.post { cb("") } }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { handler.post { cb("") } }
            })
            t.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "wake")
        } else cb("")
    }

    // ---- Söz eşleştirme ----

    private val HEY = setOf("hey", "hei", "hay", "hej", "hee", "ey", "he", "хей", "эй", "гей")

    /** Cümlede "hey <kelime>" varsa sonrasındaki kısmı döndürür (boş olabilir); yoksa null. */
    fun match(said: String): String? {
        val tokens = norm(said).split(' ').filter { it.isNotBlank() }
        val target = norm(word).replace(" ", "")
        if (target.isEmpty()) return null
        for (i in tokens.indices) {
            if (tokens[i] !in HEY) continue
            // Kelime tanıyıcıda birkaç parçaya bölünebilir ("new rex"): 1-3 parça birleştirilip denenir
            for (k in 1..3) {
                if (i + k >= tokens.size) break
                val cand = tokens.subList(i + 1, i + 1 + k).joinToString("")
                if (similar(cand, target)) return tokens.drop(i + 1 + k).joinToString(" ")
            }
        }
        // "heynewrex" gibi bitişik duyulduysa
        val joined = tokens.joinToString("")
        if (HEY.any { it.length >= 3 && joined.contains(it + target) }) return ""
        return null
    }

    private fun norm(s: String) = s.lowercase(I18n.locale())
        .replace('ı', 'i').replace('ş', 's').replace('ç', 'c').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o')
        .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Benzerlik: harf farkı kelime uzunluğunun ~%30'unu geçmesin (yanlış duyulmaya tolerans). */
    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 2) return false
        val d = levenshtein(a, b)
        return d <= maxOf(1, (b.length * 0.34f).toInt())
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
