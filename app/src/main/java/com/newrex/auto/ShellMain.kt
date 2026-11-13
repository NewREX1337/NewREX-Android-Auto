// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import java.io.File
import kotlin.system.exitProcess

/**
 * NewREX'in kendi yardımcı süreci (Shizuku'ya gerek kalmadan). Telefonun "Kablosuz hata ayıklama" bağlantısıyla,
 * shell (ADB) yetkisinde bir kez başlatılır ve telefon kapanana kadar çalışır. İçinde sadece [ShellService] var;
 * NewREX her açıldığında ona kendini bağlar. İnternete bağlanmaz, veri okumaz.
 *
 * Başlatma: CLASSPATH=<apk> app_process /system/bin com.newrex.auto.ShellMain <paket> <apk>
 */
object ShellMain {
    const val VERSION = 1

    // Yardımcı süreç shell yetkisinde çalışır; uygulamalara konan gizli arayüz kısıtı burada geçerli değil
    @android.annotation.SuppressLint("BlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        val pkg = args.getOrNull(0) ?: exitProcess(1)
        val apk = args.getOrNull(1) ?: exitProcess(1)
        Looper.prepareMainLooper()
        // Sistem bağlamı ana iş parçacığında bir kez kurulur (ekran ve dokunma işleri bunu kullanır)
        runCatching {
            val at = Class.forName("android.app.ActivityThread")
            if (at.getMethod("currentActivityThread").invoke(null) == null) {
                val o = at.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                at.getDeclaredField("sCurrentActivityThread").apply { isAccessible = true }.set(null, o)
            }
            val cur = at.getMethod("currentActivityThread").invoke(null)
            // Samsung ve Android 12+: ekran bilgisi okunurken yapılandırma denetleyicisi beklenir
            runCatching {
                val cc = Class.forName("android.app.ConfigurationController")
                val ctrl = cc.getDeclaredConstructor(Class.forName("android.app.ActivityThreadInternal"))
                    .apply { isAccessible = true }.newInstance(cur)
                at.getDeclaredField("mConfigurationController").apply { isAccessible = true }.set(cur, ctrl)
            }
            at.getMethod("getSystemContext").invoke(cur)
        }.onFailure { android.util.Log.w("NewREX", "Yardımcı: sistem bağlamı kurulamadı", it) }
        val svc = ShellService()
        Thread({ watch(pkg, apk, svc) }, "newrex-watch").apply { isDaemon = true }.start()
        Looper.loop()
    }

    /**
     * NewREX'in süreci her yeniden açıldığında yardımcıyı ona teslim eder. Uygulama açıkken sadece kendi süreç
     * dosyasına bakılır (ucuz); kapalıyken birkaç saniyede bir süreç listesine bakılır.
     */
    private fun watch(pkg: String, apk: String, svc: ShellService) {
        var delivered = -1
        while (true) {
            // NewREX güncellendi ya da kaldırıldı: eski kodla çalışmaya devam etme
            if (!File(apk).exists()) { System.err.println("apk yok: $apk"); exitProcess(0) }
            val alive = delivered > 0 && cmdline(delivered) == pkg
            if (!alive) {
                delivered = -1
                val pid = findPid(pkg)
                if (pid > 0 && deliver(pkg, svc)) delivered = pid
            }
            Thread.sleep(if (delivered > 0) 3000 else 2500)
        }
    }

    private fun cmdline(pid: Int): String? = runCatching {
        File("/proc/$pid/cmdline").readBytes().takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    }.getOrNull()

    private fun findPid(pkg: String): Int {
        val dirs = File("/proc").list() ?: return -1
        for (d in dirs) {
            val pid = d.toIntOrNull() ?: continue
            if (pid < 1000) continue
            if (cmdline(pid) == pkg) return pid
        }
        return -1
    }

    /** NewREX'in [ShellProvider]'ına yardımcının bağlantısını gönderir. */
    private fun deliver(pkg: String, svc: ShellService): Boolean = runCatching {
        val authority = "$pkg.shell"
        val sm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        val am = Class.forName("android.app.IActivityManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, sm)!!
        val token = Binder()
        val get = am.javaClass.getMethod("getContentProviderExternal", String::class.java, Int::class.javaPrimitiveType,
            IBinder::class.java, String::class.java)
        val holder = get.invoke(am, authority, 0, token, "newrex") ?: return false
        try {
            val provider = holder.javaClass.getField("provider").get(holder) ?: return false
            val extras = Bundle().apply { putBinder("binder", svc); putInt("version", VERSION) }
            // Aynı adla birden çok sürüm var; en çok parametre alanı asıl sistem çağrısıdır
            val call = provider.javaClass.methods.filter { it.name == "call" && it.parameterTypes.last() == Bundle::class.java }
                .maxByOrNull { it.parameterTypes.size } ?: return false
            val p = call.parameterTypes
            val result = when {
                // Android 12+: (AttributionSource, authority, method, arg, extras)
                p.size == 5 && p[0] != String::class.java -> {
                    val src = android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build()
                    call.invoke(provider, src, authority, "binder", null, extras)
                }
                // Android 11: (paket, özellik, authority, method, arg, extras)
                p.size == 6 -> call.invoke(provider, "com.android.shell", null, authority, "binder", null, extras)
                // Android 10: (paket, authority, method, arg, extras)
                else -> call.invoke(provider, "com.android.shell", authority, "binder", null, extras)
            } as Bundle?
            result?.getBoolean("ok") == true
        } finally {
            runCatching {
                am.javaClass.getMethod("removeContentProviderExternalAsUser", String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
                    .invoke(am, authority, token, 0)
            }.recoverCatching {
                am.javaClass.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java).invoke(am, authority, token)
            }
        }
    }.onFailure { android.util.Log.w("NewREX", "Yardımcı teslim edilemedi", it); System.err.println("deliver: $it") }.getOrDefault(false)
}
