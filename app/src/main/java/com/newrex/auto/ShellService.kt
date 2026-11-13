// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.SystemClock
import android.view.Surface
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Shizuku'nun açtığı ayrı süreçte, telefonun "shell" (ADB) yetkisiyle çalışır. Sadece üç iş yapar: telefon
 * uygulamasını araç ekranında açmak/geri almak için "am" komutu, araç ekranındaki uygulamaya dokunma ve geri
 * tuşu göndermek. İnternete bağlanmaz, veri okumaz.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        System.err.println("destroy çağrıldı")
        runCatching { display?.release() }
        closeDevice()
        System.exit(0)
    }

    // ---- Araç ekranı (kilitliyken de açık) ----

    private var display: VirtualDisplay? = null

    /** Shell adına çalışan bağlam: ekran yöneticisi isteği "com.android.shell" paketinden geliyor görsün. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = "com.android.shell"
        override fun getOpPackageName() = "com.android.shell"
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): android.content.AttributionSource =
            android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
    }

    private fun systemContext(): Context {
        val at = Class.forName("android.app.ActivityThread")
        val cur = at.getMethod("currentActivityThread").invoke(null) ?: run {
            val o = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, o)
            o
        }
        return at.getMethod("getSystemContext").invoke(cur) as Context
    }

    /** Sanal cihaz (Android Auto'nun kendi ekranı gibi): ekranı ayrı güç grubunda olur, telefon uyuyunca uyumaz. */
    private var device: Any? = null

    override fun displayStaysAwake() = device != null

    /** "Uygulama yayını" profilli eşlik eden cihaz kaydı (yoksa bir kez oluşturulur); kayıt numarası. */
    private fun association(): Int? {
        val user = exec("am get-current-user").trim().toIntOrNull() ?: 0
        fun find() = exec("cmd companiondevice list $user").lineSequence()
            .map { it.split('|').map { p -> p.trim() } }
            .firstOrNull { it.size >= 3 && it[1] == "com.android.shell" && it[2].equals(MAC, ignoreCase = true) }
            ?.get(0)?.toIntOrNull()
        find()?.let { return it }
        exec("cmd companiondevice associate $user com.android.shell $MAC android.app.role.COMPANION_DEVICE_APP_STREAMING")
        return find()
    }

    private fun createOnVirtualDevice(ctx: Context, name: String, width: Int, height: Int, dpi: Int, surface: Surface, flags: Int): VirtualDisplay? {
        val assoc = association() ?: return null
        val b = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "virtualdevice")
            as android.os.IBinder? ?: return null
        val ivdm = Class.forName("android.companion.virtual.IVirtualDeviceManager")
        val svc = Class.forName("android.companion.virtual.IVirtualDeviceManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, b)
        val vdmC = Class.forName("android.companion.virtual.VirtualDeviceManager")
        val vdm = vdmC.getDeclaredConstructor(ivdm, Context::class.java).apply { isAccessible = true }.newInstance(svc, ctx)
        val pb = Class.forName("android.companion.virtual.VirtualDeviceParams\$Builder")
        val params = pb.getMethod("build").invoke(pb.getConstructor().newInstance())
        val dev = vdmC.getMethod("createVirtualDevice", Int::class.javaPrimitiveType, params.javaClass).invoke(vdm, assoc, params)
        val cfg = android.hardware.display.VirtualDisplayConfig.Builder(name, width, height, dpi).setSurface(surface).setFlags(flags).build()
        val vd = runCatching {
            dev.javaClass.getMethod("createVirtualDisplay", android.hardware.display.VirtualDisplayConfig::class.java,
                java.util.concurrent.Executor::class.java, VirtualDisplay.Callback::class.java)
                .invoke(dev, cfg, java.util.concurrent.Executor { it.run() }, null) as VirtualDisplay?
        }.getOrNull()
        if (vd == null) { runCatching { dev.javaClass.getMethod("close").invoke(dev) }; return null }
        device = dev
        return vd
    }

    private fun closeDevice() {
        val d = device ?: return
        device = null
        runCatching { d.javaClass.getMethod("close").invoke(d) }
    }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface): Int = runCatching {
        runCatching { display?.release() }
        display = null
        closeDevice()
        val ctx = ShellContext(systemContext())
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ctx)
        val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        // Güvenilir (1 shl 10), kendi ekran grubu (1 shl 11), kilitliyken açık (1 shl 12), dokunma titreşimi yok
        // (1 shl 13), kendi odağı (1 shl 14). Telefon izin vermezse daha azıyla denenir.
        val tries = intArrayOf(
            base or (1 shl 10) or (1 shl 11) or (1 shl 12) or (1 shl 13) or (1 shl 14),
            base or (1 shl 10) or (1 shl 11) or (1 shl 12),
            base or (1 shl 10),
        )
        // Önce sanal cihazla (telefon ekranı kapanınca da açık kalır); olmazsa normal ekran
        var vd: VirtualDisplay? = runCatching { createOnVirtualDevice(ctx, name, width, height, dpi, surface, tries[0] and (1 shl 11).inv()) }
            .onFailure { android.util.Log.w("NewREX", "Sanal cihaz ekranı olmadı", it) }.getOrNull()
        if (vd == null) for (f in tries) {
            vd = runCatching { dm.createVirtualDisplay(name, width, height, dpi, surface, f) }
                .onFailure { android.util.Log.w("NewREX", "Ekran olmadı ($f)", it) }.getOrNull()
            if (vd != null) break
        }
        display = vd
        val id = vd?.display?.displayId ?: -1
        android.util.Log.i("NewREX", "Yardımcı ekran kurdu: $id (sanal cihaz: ${device != null})")
        // Araç ekranındaki uygulamaların klavyesi (WhatsApp vb.) telefonda değil araç ekranında açılsın
        if (id >= 0) runCatching {
            val sm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window")
            val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, sm)!!
            // WindowManager.DISPLAY_IME_POLICY_LOCAL = 0
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, id, 0)
        }
        id
    }.onFailure { android.util.Log.w("NewREX", "createDisplay hata", it) }.getOrDefault(-1)

    override fun resizeDisplay(width: Int, height: Int, dpi: Int) {
        runCatching { display?.resize(width, height, dpi) }
    }

    override fun setDisplaySurface(surface: Surface?) {
        runCatching { display?.surface = surface }
    }

    override fun releaseDisplay() {
        runCatching { display?.release() }
        display = null
        closeDevice()
    }

    override fun exec(cmd: String): String = runCatching {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    }.getOrElse { "HATA: $it" }

    override fun touch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long, displayId: Int): Boolean = runCatching {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        val ok = inject(ev, displayId)
        ev.recycle()
        ok
    }.getOrDefault(false)

    override fun key(keyCode: Int, displayId: Int) {
        runCatching {
            val now = SystemClock.uptimeMillis()
            for (a in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val ev = KeyEvent(now, now, a, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_KEYBOARD)
                inject(ev, displayId)
            }
        }
    }

    private fun inject(ev: InputEvent, displayId: Int): Boolean {
        setDisplayId.invoke(ev, displayId)
        return injector.second.invoke(injector.first, ev, 0) as Boolean
    }

    private companion object {
        /** NewREX'in eşlik eden cihaz kaydının adresi (gerçek bir cihaz değil, sadece kayıt anahtarı). */
        const val MAC = "02:4E:58:00:00:03"
        val setDisplayId: Method by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

        /** Android 14+ InputManagerGlobal, öncesi InputManager. */
        val injector: Pair<Any, Method> by lazy {
            runCatching {
                val c = Class.forName("android.hardware.input.InputManagerGlobal")
                c.getMethod("getInstance").invoke(null)!! to
                    c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }.getOrElse {
                val c = Class.forName("android.hardware.input.InputManager")
                c.getMethod("getInstance").invoke(null)!! to
                    c.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            }
        }
    }
}
