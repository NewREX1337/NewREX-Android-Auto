// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * King Installer'ın yaptığını uygulamanın içinden yapar: NewREX kendi APK'sını Google'ın paket yükleyicisine
 * "Play Store kuruyor" bilgisiyle tekrar kurdurur. Android Auto bu kayda bakıp uygulamayı gösteriyor.
 * Xiaomi gibi telefonlarda kendi yükleyicileri araya giriyor; o yüzden Google'ın yükleyicisi adıyla çağrılır.
 */
object SelfInstall {
    const val PLAY = "com.android.vending"

    /** Android Auto'nun "sistem yükleyicisi" saydığı paketler, öncelik sırasıyla. */
    val INSTALLERS = listOf("com.google.android.packageinstaller", "com.android.packageinstaller")

    enum class State { READY, DISABLED, MISSING }

    fun state(context: Context, pkg: String): State {
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
            ?: return State.MISSING
        if (!info.enabled) return State.DISABLED
        val probe = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(Uri.parse("content://x/y.apk"), MIME).setPackage(pkg)
        return if (pm.queryIntentActivities(probe, 0).isEmpty()) State.MISSING else State.READY
    }

    /** Telefonda Android Auto'nun kabul ettiği bir yükleyici kullanılabilir mi. */
    fun available(context: Context) = INSTALLERS.firstOrNull { state(context, it) == State.READY }

    fun canRequestInstalls(context: Context) =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Kendi APK'sını [installer] ile yeniden kurar. Kurulum bitince uygulama yeniden başlar. */
    fun start(activity: Activity, installer: String): Boolean {
        val dir = File(activity.cacheDir, "self").apply { mkdirs() }
        val apk = File(dir, "NewREX.apk")
        File(activity.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, MIME)
            .setPackage(installer)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        return runCatching { activity.startActivity(intent) }.isSuccess
    }

    /** Yardım ekranı için yükleyicilerin durumu. */
    fun report(context: Context): String = INSTALLERS.joinToString("\n") { pkg ->
        val s = when (state(context, pkg)) {
            State.READY -> "hazır"
            State.DISABLED -> "telefonda kapatılmış"
            State.MISSING -> "yok"
        }
        "• $pkg: $s"
    }

    private const val MIME = "application/vnd.android.package-archive"
}
