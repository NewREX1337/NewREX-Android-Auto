// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi

class NewRexCarAppService : CarAppService() {
    // Araç tarafını sadece Google'ın imzaladığı gerçek Android Auto açabilsin; başka bir uygulama
    // kendini Android Auto gibi gösteremesin. Test sürümünde simülatörler için serbest.
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        else HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(): Session = NewRexSession()
}

class NewRexSession : Session() {
    private var debugReceiver: BroadcastReceiver? = null

    override fun onCreateScreen(intent: Intent): Screen {
        Prefs.init(carContext)
        PlayerHub.init(carContext.applicationContext)
        DriveState.start(carContext)
        VehicleData.start(carContext)
        com.newrex.auto.ui.SessionAlerts.reset()
        if (BuildConfig.DEBUG) registerDebugReceiver()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // Araç kapandı: son konumu park yeri olarak kaydet, yansıtmayı bitir
                ParkSaver.save(carContext)
                Mirror.stop(carContext)
                VehicleData.stop()
                DriveState.stop()
                PlayerHub.release()
                debugReceiver?.let { carContext.unregisterReceiver(it) }
            }
        })
        return SurfaceScreen(carContext)
    }

    /** Test için: adb shell am broadcast -a com.newrex.auto.DEBUG_DRIVE --ez driving true */
    private fun registerDebugReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                DriveState.forced = if (intent.hasExtra("driving")) intent.getBooleanExtra("driving", false) else null
            }
        }
        ContextCompat.registerReceiver(carContext, r, IntentFilter("com.newrex.auto.DEBUG_DRIVE"),
            ContextCompat.RECEIVER_EXPORTED)
        debugReceiver = r
    }
}

/**
 * Android Auto bize çizim yapabileceğimiz bir yüzey (surface) verir. Bu yüzeyin üzerinde sanal bir ekran
 * açıp normal Android arayüzümüzü (kartlar, video, web) orada gösteriyoruz. Araç ekranındaki dokunmalar
 * da bu sanal ekrana dokunma olarak aktarılır.
 */
class SurfaceScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {
    private val handler = Handler(Looper.getMainLooper())
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var ui: CarUi? = null
    private var width = 0
    private var height = 0
    private var visibleArea: Rect? = null

    // Kaydırma hareketini sahte bir parmak sürüklemesine çeviriyoruz
    private var dragStart = 0L
    private var dragX = 0f
    private var dragY = 0f
    private val endDrag = Runnable { finishDrag() }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = teardown()
        })
    }

    override fun onGetTemplate(): Template {
        val home = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_home)).build())
            .setOnClickListener { ui?.home() }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(home).build())
            .build()
    }

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        teardown()
        val surface = container.surface ?: return
        width = container.width
        height = container.height
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "NewREX", width, height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        )
        virtualDisplay = vd
        val p = Presentation(carContext, vd.display, R.style.Theme_NewREX_Car)
        presentation = p
        buildUi()
        try {
            p.show()
        } catch (e: Exception) {
            Log.e(TAG, "Araç ekranı açılamadı", e)
        }
        Log.i(TAG, "Yüzey ${width}x$height dpi=${container.dpi}")
    }

    private fun buildUi() {
        val p = presentation ?: return
        ui?.destroy()
        val newUi = CarUi(p.context) { handler.post { buildUi(); ui?.push(com.newrex.auto.pages.SettingsPage(ui!!)) } }
        ui = newUi
        p.setContentView(newUi.root)
        applyInsets()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        this.visibleArea = visibleArea
        applyInsets()
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(container: SurfaceContainer) = teardown()

    private fun applyInsets() {
        val a = visibleArea ?: return
        if (width == 0) return
        // Üstteki şerit sadece Android Auto'nun sağ üst köşedeki butonu için ayrılıyor; tüm şeridi boş
        // bırakmak yerine üst barda sağda o kadar yer açıyoruz.
        ui?.setInsets(a.left, 0, (width - a.right).coerceAtLeast(0), (height - a.bottom).coerceAtLeast(0),
            topRightReserve = a.top)
        Log.i(TAG, "Görünür alan $a")
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        ui?.destroy()
        ui = null
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // ---- Dokunma aktarımı ----

    override fun onClick(x: Float, y: Float) {
        if (dragStart != 0L) finishDrag()
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, t, t)
        inject(MotionEvent.ACTION_UP, x, y, t, t + 60)
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        val now = SystemClock.uptimeMillis()
        if (dragStart == 0L) {
            dragStart = now
            dragX = width / 2f
            dragY = height / 2f
            inject(MotionEvent.ACTION_DOWN, dragX, dragY, dragStart, now)
        }
        dragX -= distanceX
        dragY -= distanceY
        inject(MotionEvent.ACTION_MOVE, dragX, dragY, dragStart, now)
        handler.removeCallbacks(endDrag)
        handler.postDelayed(endDrag, 120)
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (dragStart != 0L) finishDrag()
    }

    private fun finishDrag() {
        handler.removeCallbacks(endDrag)
        if (dragStart == 0L) return
        inject(MotionEvent.ACTION_UP, dragX, dragY, dragStart, SystemClock.uptimeMillis())
        dragStart = 0L
    }

    private fun inject(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val root = presentation?.window?.decorView ?: return
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private companion object {
        const val TAG = "NewREX"
    }
}
