// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.net.Uri
import android.provider.Settings
import android.view.Display
import android.view.Surface
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

    private val handler = Handler(Looper.getMainLooper())
    private var idleStop: Runnable? = null

    /** Yansıtma sayfası kapandıktan 3 dakika sonra paylaşımı bitirir (sayfaya dönülürse iptal). */
    fun scheduleIdleStop(context: Context) {
        cancelIdleStop()
        if (!active.value) return
        idleStop = Runnable { idleStop = null; stop(context) }.also { handler.postDelayed(it, 3 * 60_000L) }
    }

    fun cancelIdleStop() {
        idleStop?.let { handler.removeCallbacks(it) }
        idleStop = null
    }

    fun stop(context: Context) {
        cancelIdleStop()
        if (active.value) context.startService(Intent(context, MirrorService::class.java).setAction(MirrorService.ACTION_STOP))
    }

    internal fun onStopped() {
        display?.release()
        display = null
        projection = null
        contentSize = null
        active.value = false
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
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification())

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
        PhoneRotation.restore(this)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Mirror.projection?.stop()
        Mirror.onStopped()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Ekran yansıtma"), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(t("Ekran araca yansıtılıyor"))
            .setContentText(t("Görüntü sadece araç ekranında gösterilir, kaydedilmez."))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, t("Durdur"), stop).build())
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
 * Yansıtma sırasında telefonu yan çevirir ki görüntü geniş araç ekranını kaplasın.
 * Telefonun önceki dönme ayarı saklanır ve yansıtma bitince (ya da uygulama kapanmışsa
 * bir sonraki açılışta) geri yüklenir. "Sistem ayarlarını değiştirme" izni gerekir.
 */
object PhoneRotation {
    private const val FILE = "mirror_rotation"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun canWrite(c: Context) = Settings.System.canWrite(c)

    /** Kullanıcının tercihi: yansıtırken telefon yatay mı dursun (varsayılan evet). */
    fun wantLandscape(c: Context) = sp(c).getBoolean("landscape", true)

    fun setWantLandscape(c: Context, v: Boolean) { sp(c).edit().putBoolean("landscape", v).apply() }

    /** Yansıtma görünürken çağrılır. */
    fun apply(c: Context): Boolean {
        if (!canWrite(c)) return false
        val cr = c.contentResolver
        return runCatching {
            val p = sp(c)
            if (!p.getBoolean("saved", false)) {
                p.edit().putBoolean("saved", true)
                    .putInt("acc", Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1))
                    .putInt("rot", Settings.System.getInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0))
                    .commit()
            }
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(cr, Settings.System.USER_ROTATION,
                if (wantLandscape(c)) Surface.ROTATION_90 else Surface.ROTATION_0)
        }.isSuccess
    }

    /** Telefonun dönme ayarını yansıtmadan önceki haline döndürür. */
    fun restore(c: Context) {
        val p = sp(c)
        if (!p.getBoolean("saved", false)) return
        if (canWrite(c)) runCatching {
            val cr = c.contentResolver
            Settings.System.putInt(cr, Settings.System.USER_ROTATION, p.getInt("rot", Surface.ROTATION_0))
            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, p.getInt("acc", 1))
        }
        p.edit().putBoolean("saved", false).apply()
    }

    /** Uygulama beklenmedik şekilde kapandıysa telefon yan kalmasın. */
    fun restoreIfStale(c: Context) {
        if (!Mirror.active.value) restore(c)
    }

    /** Telefonda izin ekranını açar. */
    fun requestPermission(c: Context) {
        runCatching {
            c.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
