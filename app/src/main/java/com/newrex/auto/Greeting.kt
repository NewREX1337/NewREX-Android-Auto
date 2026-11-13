// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.newrex.auto.data.Prefs
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Araca bağlanınca sesli karşılama: "Günaydın Berk, NewREX'e hoş geldin! İyi yolculuklar!"
 * Mikrofon izni gerektirmez; yalnızca TTS kullanır. Açılmadıysa hiçbir şey yapmaz.
 */
object Greeting {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var focusRequest: AudioFocusRequest? = null

    fun speak(context: Context) {
        if (!Prefs.greetingEnabled) return
        if (Prefs.greetingOncePerDay) {
            val today = LocalDate.now().toEpochDay()
            if (Prefs.lastGreetingDay >= today) return
            Prefs.lastGreetingDay = today
        }

        val text = buildText()
        val appContext = context.applicationContext

        if (tts != null && ready) {
            doSpeak(appContext, text)
            return
        }
        // Motor hâlâ açılıyorsa ikinci bir TTS açılıp ilki sızmasın
        if (tts != null) return

        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = tts ?: return@TextToSpeech
                val result = engine.setLanguage(I18n.locale())
                ready = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (ready) doSpeak(appContext, text)
                else Log.w("NewREX", "TTS dili yok, karşılama atlanıyor")
            }
        }
    }

    private fun buildText(): String {
        val name = Prefs.greetingName.trim()
        val hour = LocalTime.now().hour
        val timeGreeting = when {
            hour < 6 -> t("İyi geceler")
            hour < 12 -> t("Günaydın")
            hour < 18 -> t("İyi günler")
            else -> t("İyi akşamlar")
        }
        val nameStr = if (name.isNotEmpty()) " $name" else ""
        return t("{0}{1}, NewREX'e hoş geldin! İyi yolculuklar!", timeGreeting, nameStr)
    }

    private fun doSpeak(context: Context, text: String) {
        val engine = tts ?: return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Çalan müziğin sesini kıs
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = releaseFocus(am)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = releaseFocus(am)
        })

        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "newrex_greeting")
    }

    private fun releaseFocus(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
