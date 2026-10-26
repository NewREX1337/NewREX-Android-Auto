// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.newrex.auto.data.Prefs

/**
 * NewREX arabaya iki kapıdan girebilir; aynı anda ikisi açık olursa Android Auto park kapısını yok sayar:
 * - Normal: her zaman çalışır, ama Android Auto bunu sadece Play Store / King Installer kurulumunda gösterir.
 * - Park: Android 15+, "Bilinmeyen kaynaklar" ile her kurulumda görünür; sadece park halindeyken açılır.
 * Otomatik modda kurulum şekline göre doğru kapı seçilir.
 */
object CarEntry {
    const val AUTO = 0
    const val NORMAL = 1
    const val PARKED = 2

    fun parkedSupported() = Build.VERSION.SDK_INT >= 35

    /** Play Store ya da King Installer ile kurulmuş mu (Android Auto'nun normal kapıyı göstermesi için şart). */
    fun trustedInstall(context: Context): Boolean = runCatching {
        val installer = if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
        installer == "com.android.vending"
    }.getOrDefault(false)

    /** Şu an kullanılan kapı. */
    fun effective(context: Context): Int = if (BuildConfig.PARK_ONLY) PARKED else when (Prefs.carEntry) {
        NORMAL -> NORMAL
        PARKED -> if (parkedSupported()) PARKED else NORMAL
        else -> if (!trustedInstall(context) && parkedSupported()) PARKED else NORMAL
    }

    fun apply(context: Context) {
        // Park sürümünde normal kapı hiç yok
        if (BuildConfig.PARK_ONLY) return
        val parked = effective(context) == PARKED
        set(context, NewRexCarAppService::class.java, !parked)
        set(context, CarActivity::class.java, parked)
    }

    private fun set(context: Context, cls: Class<*>, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val cn = ComponentName(context, cls)
        if (context.packageManager.getComponentEnabledSetting(cn) == state) return
        context.packageManager.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
    }
}

/** Uygulama güncellenince (ör. King Installer ile tekrar kurulunca) doğru kapıyı hemen seçer. */
class CarEntryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Prefs.init(context)
        CarEntry.apply(context)
    }
}
