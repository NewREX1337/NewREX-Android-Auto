// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi

/**
 * Android Auto'nun "park halindeyken" uygulama kapısı (Android 15+). Bu kapı Play Store dışından kurulan
 * uygulamalarda da Android Auto'daki "Bilinmeyen kaynaklar" ayarıyla açılır; King Installer'ın çalışmadığı
 * telefonlarda (Xiaomi, Tecno...) NewREX arabada buradan görünür. Ekran, araç ekranında doğrudan çalışan
 * normal bir uygulama ekranıdır; sürüş başlayınca Android Auto bu kapıyı kendisi kapatır.
 */
class CarActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var ui: CarUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        com.newrex.auto.data.CarLog.init(this)
        Prefs.lastCarSession = System.currentTimeMillis()
        Prefs.lastParkedSession = System.currentTimeMillis()
        PlayerHub.init(applicationContext)
        DriveState.start(this)
        com.newrex.auto.ui.SessionAlerts.reset()
        buildUi()
    }

    private fun buildUi(startOnApps: Boolean = false) {
        ui?.destroy()
        val newUi = CarUi(this, startOnApps) { openSettings ->
            handler.post {
                buildUi(openSettings || ui?.onApps == true)
                if (openSettings) ui?.push(com.newrex.auto.pages.SettingsPage(ui!!))
            }
        }
        ui = newUi
        setContentView(newUi.root)
        newUi.setInsets(0, 0, 0, 0)
    }

    override fun onResume() {
        super.onResume()
        ui?.setSurfaceVisible(true)
    }

    override fun onPause() {
        ui?.setSurfaceVisible(false)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val u = ui
        if (u != null && u.pageCount > 1) u.back() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ui?.destroy()
        ui = null
        if (isFinishing) {
            TripSaver.save()
            ParkSaver.save(this)
            DriveState.stop()
            PlayerHub.release()
        }
        super.onDestroy()
    }
}
