// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.io.File

/**
 * Android Auto, gerçek araçta yalnızca Play Store'dan yüklenmiş görünen uygulamaları listeliyor.
 * Shizuku (kullanıcının kendi başlattığı, bilgisayar bağlantısı yetkisi veren uygulama) çalışıyorsa
 * NewREX kendi APK'sını "Play Store yükledi" bilgisiyle yeniden kurar. Veriler silinmez.
 */
object SelfInstall {
    const val PLAY = "com.android.vending"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val LOG = "/data/local/tmp/newrex_install.log"

    enum class State { DONE, NEED_SHIZUKU_APP, SHIZUKU_NOT_RUNNING, NEED_PERMISSION, READY }

    fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    fun isPlayInstalled(context: Context) = installer(context) == PLAY

    fun shizukuInstalled(context: Context) =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false)

    fun state(context: Context): State = when {
        isPlayInstalled(context) -> State.DONE
        !shizukuInstalled(context) -> State.NEED_SHIZUKU_APP
        !runCatching { Shizuku.pingBinder() }.getOrDefault(false) || Shizuku.isPreV11() -> State.SHIZUKU_NOT_RUNNING
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> State.NEED_PERMISSION
        else -> State.READY
    }

    /** Önceki denemenin kaydı (Shizuku tarafında yazılır, herkes okuyabilir). */
    fun lastLog(): String? = runCatching { File(LOG).readText().trim().takeLast(400) }.getOrNull()?.ifBlank { null }

    /**
     * Yeniden kurulum, NewREX'in kendi işlemini kapatır. Bu yüzden komut Shizuku tarafında arka planda
     * (bizim uygulamadan bağımsız) çalışır ve bitince NewREX'i yeniden açar.
     */
    fun run(context: Context) {
        val pkg = context.packageName
        val src = context.applicationInfo.sourceDir
        val apk = "/data/local/tmp/newrex_self.apk"
        val script = listOf(
            "cp $src $apk && chmod 644 $apk",
            "pm install -r -t -i $PLAY $apk",
            // Android 14+ güncellemede yükleyici bilgisini değiştirmezse: verileri koruyarak kaldır, yeniden kur
            "if ! dumpsys package $pkg | grep -q 'installerPackageName=$PLAY'; then pm uninstall -k $pkg && pm install -t -i $PLAY $apk; fi",
            "dumpsys package $pkg | grep -m1 installerPackageName",
            "rm -f $apk",
            "am force-stop com.google.android.projection.gearhead",
            "am start -n $pkg/.MainActivity",
            "chmod 644 $LOG",
        ).joinToString("; ")
        val command = "nohup sh -c \"$script\" > $LOG 2>&1 &"
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
        ).apply { isAccessible = true }
        val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        process.waitFor()
    }
}
