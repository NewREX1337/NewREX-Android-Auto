// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.newrex.auto.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tasarruf modu: sadece süsleri kapatır. Açıkken arka plan sabit durur, dokunma parlamaları ve kayan
 * animasyonlar kapanır. TV/radyo/video kalitesine dokunmaz (TV kalitesi ayrı bir ayar). "Otomatik"te telefon
 * pil tasarrufundayken ya da ısındığında kendiliğinden açılır.
 */
object Saver {
    const val OFF = 0
    const val ON = 1
    const val AUTO = 2
    val NAMES = listOf("Kapalı", "Açık", "Otomatik")

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    /** Hızlı kontrol için (çizim kodunda). */
    val on: Boolean get() = _active.value

    private var app: Context? = null

    fun init(context: Context) {
        if (app != null) { update(); return }
        val c = context.applicationContext
        app = c
        ContextCompat.registerReceiver(c, object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) = update()
        }, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (Build.VERSION.SDK_INT >= 29) {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager).addThermalStatusListener(c.mainExecutor) { update() }
        }
        update()
    }

    /** Neden açık olduğu (ayar ekranında gösterilir); kapalıysa null. */
    fun reason(): String? {
        val c = app ?: return null
        return when (Prefs.saverMode) {
            ON -> "elle açıldı"
            AUTO -> when {
                powerSave(c) -> "telefon pil tasarrufunda"
                hot(c) -> "telefon ısındı"
                else -> null
            }
            else -> null
        }
    }

    fun update() {
        _active.value = reason() != null
    }

    private fun powerSave(c: Context) = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode

    private fun hot(c: Context) = Build.VERSION.SDK_INT >= 29 &&
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
}
