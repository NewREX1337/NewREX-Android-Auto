// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Mikrofonla konuşmayı yazıya çevirir (seçili uygulama dilinde). Ne yapılacağına
 * [com.newrex.auto.pages.VoiceActions] karar verir. Ses kaydedilmez; tanıma telefonun kendi servisinde yapılır.
 */
object VoiceCommand {
    val listening = MutableStateFlow(false)
    val lastResult = MutableStateFlow<String?>(null)
    val lastError = MutableStateFlow<String?>(null)

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** [onResult]: tanıyıcının olası metinleri (en olası önce, küçük harfli). */
    fun listen(context: Context, onError: ((String) -> Unit)? = null, onResult: (List<String>) -> Unit) {
        if (listening.value) { stop(); return }
        fun fail(msg: String) { lastError.value = msg; onError?.invoke(msg) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(t("Sesli komut için mikrofon izni gerekli. Telefonda NewREX'i açıp mikrofon izni ver."))
            return
        }
        if (!isAvailable(context)) {
            fail(t("Ses tanıma bu cihazda kullanılamıyor"))
            return
        }

        // Önceki tanıyıcı (sonuç gelmiş ama kapatılmamış olabilir) bırakılmadan yenisi açılmasın
        stop()
        // "Hey ..." dinleyicisi mikrofonu bıraksın; komut bitince yeniden başlar
        WakeWord.pause()
        val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
        recognizer = sr
        listening.value = true
        lastResult.value = null
        lastError.value = null

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening.value = false }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onResults(results: Bundle?) {
                release(sr)
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.map { it.lowercase(I18n.locale()).trim() }?.filter { it.isNotBlank() }.orEmpty()
                if (texts.isEmpty()) { fail(t("Ses algılanamadı, tekrar dene")); return }
                lastResult.value = texts.first()
                onResult(texts)
            }

            override fun onError(error: Int) {
                release(sr)
                fail(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> t("Ses algılanamadı, tekrar dene")
                    SpeechRecognizer.ERROR_AUDIO -> t("Mikrofon hatası")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> t("Sesli komut için mikrofon izni gerekli")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> t("İnternet bağlantısı gerekli")
                    else -> t("Ses tanıma hatası ({0})", error)
                })
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, I18n.speechTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        // Önce yumuşak "dinliyorum" sesi; mikrofon sesin ilk notasından sonra açılır (ses komuta karışmasın)
        Chime.play(rising = true)
        handler.postDelayed({ if (recognizer === sr) runCatching { sr.startListening(intent) } }, 420)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun stop() {
        val was = recognizer != null
        listening.value = false
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
        if (was) WakeWord.resume()
    }

    /** Sonuç ya da hata gelince tanıyıcı kapatılır (açık kalırsa mikrofon ve servis bağlantısı sızar). */
    private fun release(sr: SpeechRecognizer) {
        listening.value = false
        if (recognizer === sr) recognizer = null
        android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { sr.destroy() } }
        WakeWord.resume()
    }
}
