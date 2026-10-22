// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.Surface
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Telefon ekranını araç ekranına yansıtma. Görüntü Android'in kendi ekran paylaşımı izniyle alınır
 * (her seferinde telefonda onay gerekir) ve yalnızca araç ekranına çizilir; hiçbir yere kaydedilmez
 * veya gönderilmez.
 */
object Mirror {
    val active = MutableStateFlow(false)
    internal var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    /** Android 14+ paylaşılan içeriğin boyutunu bildirir. */
    internal var contentSize: Point? = null

    fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        val p = projection ?: return
        val d = display
        if (d == null) {
            // Android 14'ten itibaren bir izinle yalnızca bir sanal ekran açılabiliyor; sonra yüzeyi değiştiriyoruz
            display = runCatching {
                p.createVirtualDisplay("NewREX-Yansıtma", width, height, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            }.getOrNull()
        } else {
            d.resize(width, height, dpi)
            d.surface = surface
        }
    }

    fun detach() {
        display?.surface = null
    }

    /** Telefon ekranının (o anki yönüyle) gerçek boyutu. */
    fun phoneSize(context: Context): Point {
        contentSize?.let { return it }
        val p = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return p
    }

    fun requestStart(context: Context) {
        context.startActivity(Intent(context, MirrorPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun stop(context: Context) {
        if (active.value) context.startService(Intent(context, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }

    internal fun onStopped() {
        display?.release()
        display = null
        projection = null
        contentSize = null
        active.value = false
    }

    fun touchEnabled(context: Context): Boolean {
        val name = ComponentName(context, MirrorTouchService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { it.equals(name, ignoreCase = true) }
    }
}

/** Telefonda ekran paylaşımı onayını isteyen görünmez ekran. */
class MirrorPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Dokunmaların doğru yere gitmesi için tek uygulama yerine tüm ekran paylaşılır
        val intent = if (Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK && data != null) {
            startForegroundService(Intent(this, MirrorService::class.java)
                .putExtra(MirrorService.EXTRA_CODE, resultCode)
                .putExtra(MirrorService.EXTRA_DATA, data))
        }
        finish()
    }

    private companion object {
        const val REQUEST = 41
    }
}

/** Yansıtma sürerken çalışan servis (Android bunu bildirimle göstermeyi zorunlu tutar). */
class MirrorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)

        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val projection = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (projection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val old = Mirror.projection
        Mirror.onStopped()
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // Kullanıcı bildirimden ya da sistemden durdurdu
                if (Mirror.projection !== projection) return
                Mirror.onStopped()
                stopSelf()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                Mirror.contentSize = Point(width, height)
            }
        }, Handler(Looper.getMainLooper()))
        Mirror.projection = projection
        Mirror.active.value = true
        old?.stop()
        wakeLock?.let { if (it.isHeld) it.release() }

        // Telefon ekranı kapanırsa görüntü de gider; yansıtma boyunca ekran açık kalsın
        @Suppress("DEPRECATION")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "newrex:mirror")
            .apply { acquire(4 * 60 * 60_000L) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Mirror.projection?.stop()
        Mirror.onStopped()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle("Ekran araca yansıtılıyor")
            .setContentText("Görüntü sadece araç ekranında gösterilir, kaydedilmez.")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.newrex.auto.MIRROR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "mirror"
        private const val NOTIFICATION_ID = 7
    }
}

/**
 * Araç ekranındaki dokunmaları telefondaki uygulamalara iletir. Ekran içeriğini okumaz
 * (canRetrieveWindowContent=false), yalnızca dokunma ve geri/ana ekran tuşu gönderebilir.
 */
class MirrorTouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun tap(x: Float, y: Float) = stroke(x, y, x, y, 50)

    fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(1, 3000)))
            .build(), null, null)
    }

    companion object {
        var instance: MirrorTouchService? = null
            private set
    }
}
