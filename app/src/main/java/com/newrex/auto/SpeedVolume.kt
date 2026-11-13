// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.media.audiofx.LoudnessEnhancer
import com.newrex.auto.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Hıza göre ses: araç hızlandıkça artan yol ve rüzgâr gürültüsüne karşı NewREX'in çaldığı sesi (TV, radyo,
 * podcast) biraz yükseltir, yavaşlayınca yumuşakça eski haline döner. Oynatıcının sesi zaten en yüksekte
 * olduğundan kazanç ses efektiyle (LoudnessEnhancer) eklenir. 30 km/s altında hiç değişmez,
 * 110 km/s ve üstünde seçilen seviyenin tamamı uygulanır.
 */
object SpeedVolume {
    /** Seviye başına en yüksek kazanç (milibel; 300 = +3 dB). */
    private val MAX_MB = intArrayOf(0, 300, 600, 900)
    fun levelNames() = listOf(t("Kapalı"), t("Az"), t("Orta"), t("Çok"))

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var enhancer: LoudnessEnhancer? = null
    private var job: Job? = null
    private var current = -1

    fun attach(audioSessionId: Int) {
        detach()
        enhancer = runCatching { LoudnessEnhancer(audioSessionId) }.getOrNull()
        job = scope.launch { DriveState.speedKmh.collect { apply(it) } }
    }

    fun settingsChanged() {
        current = -1
        apply(DriveState.speedKmh.value)
    }

    private fun apply(kmh: Float?) {
        val e = enhancer ?: return
        val max = MAX_MB.getOrElse(Prefs.speedVolume) { 0 }
        val k = (((kmh ?: 0f) - 30f) / 80f).coerceIn(0f, 1f)
        val target = (max * k / 50).toInt() * 50
        // Küçük hız oynamalarında ses sürekli değişmesin
        if (current >= 0 && abs(target - current) < 100 && target != 0 && target != max) return
        if (target == current) return
        current = target
        runCatching {
            e.setTargetGain(target)
            e.enabled = target > 0
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        runCatching { enhancer?.release() }
        enhancer = null
        current = -1
    }
}
