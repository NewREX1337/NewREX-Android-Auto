// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Araç bağlıyken çalışan küçük ön plan servisi. Tek işi: Android Auto'da başka bir uygulama (harita, müzik)
 * öndeyken de konum gelmeye devam etsin, gidilen km ve yolculuk eksiksiz sayılsın. Konum hiçbir yere gönderilmez.
 */
class DriveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Yolculuk takibi"), NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(t("Yolculuk takibi"))
            .setContentText(t("Gidilen yol sayılıyor. Konum telefonda kalır, hiçbir yere gönderilmez."))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        } catch (_: Throwable) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "drive"
        private const val ID = 31

        fun start(context: Context) {
            if (!DriveState.hasLocationPermission(context)) return
            runCatching { context.startForegroundService(Intent(context, DriveService::class.java)) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DriveService::class.java)) }
        }
    }
}
