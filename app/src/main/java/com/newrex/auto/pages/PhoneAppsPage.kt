// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Display
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.newrex.auto.Mirror
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telefonda yüklü tüm uygulamalar. Dokununca uygulama doğrudan araç ekranında açılır
 * (Shizuku ile, yansıtma yok). Liste yalnızca telefonda okunur, hiçbir yere gönderilmez.
 */
class PhoneAppsPage(ui: CarUi) : Page(ui) {
    override val title = t("Telefon uygulamaları")

    private class App(val name: String, val pkg: String, val icon: Bitmap?)

    private lateinit var column: LinearLayout
    private var apps: List<App>? = null
    private var lastWidth = 0

    override fun build(): View = with(ctx) {
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
            addView(label(t("Uygulamalar yükleniyor…"), 16f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(20), l = dp(4)))
        }
        scope.launch {
            apps = withContext(Dispatchers.IO) { loadApps() }
            fill()
        }
        ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                if (r - l != lastWidth && r - l > 0) { lastWidth = r - l; v.post { fill() } }
            }
        }
    }

    private fun loadApps(): List<App> {
        val pm = ctx.packageManager
        val size = ctx.dp(48)
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val bmp = runCatching {
                    val d = ri.loadIcon(pm)
                    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
                        d.setBounds(0, 0, size, size)
                        d.draw(Canvas(b))
                    }
                }.getOrNull()
                App(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, bmp)
            }
            .sortedBy { it.name.lowercase(com.newrex.auto.I18n.locale()) }
    }

    private fun fill(): Unit = with(ctx) {
        val list = apps ?: return
        if (lastWidth == 0) return
        column.removeAllViews()
        if (list.isEmpty()) {
            column.addView(label(t("Telefonda açılabilir uygulama bulunamadı."), 16f, Palette.TEXT_DIM),
                lp(WRAP, WRAP).margins(t = dp(20), l = dp(4)))
            return
        }
        column.addView(label(t("Dokunduğun uygulama doğrudan bu ekranda açılır."),
            14f, Palette.TEXT_DIM, lines = 2), lp(MATCH, WRAP).margins(l = dp(4), t = dp(4), b = dp(10)))
        val usable = lastWidth - column.paddingLeft - column.paddingRight
        val gap = dp(10)
        val cols = (usable / dp(120)).coerceIn(3, 8)
        list.chunked(cols).forEachIndexed { i, rowApps ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until cols) {
                val v = rowApps.getOrNull(c)?.let { tile(it) } ?: View(this)
                row.addView(v, lp(0, MATCH, 1f).margins(r = if (c < cols - 1) gap else 0))
            }
            column.addView(row, lp(MATCH, dp(104)).margins(t = if (i > 0) gap else 0))
        }
    }

    private fun tile(app: App): View = with(ctx) {
        val r = dpf(16)
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pressable(rounded(0xFF0C0E14.toInt(), r, 1, 0x1FFFFFFF), r)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            addView(ImageView(this@with).apply {
                if (app.icon != null) setImageBitmap(app.icon)
            }, lp(dp(48), dp(48)))
            addView(label(app.name, 14f, lines = 2).apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).margins(t = dp(6)))
            onTap { open(app) }
        }
    }

    private fun open(app: App) = openPhoneApp(ui, app.name, app.pkg)
}

/** Telefondaki açılabilir uygulamalar (ad, paket); simge yüklemeden, sesli komut araması için. */
fun phoneApps(ctx: android.content.Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
}

private var shizukuWarned = 0L

private val launchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)

/** Telefon uygulamasını yansıtma olmadan doğrudan araç ekranında açar (Shizuku ile). */
fun openPhoneApp(ui: CarUi, name: String, pkg: String) {
    val ctx = ui.ctx
    // Uygulama yansıtma olmadan, doğrudan araç ekranında açılır (Shizuku ile). Olmazsa nedeni yazılır.
    val direct = com.newrex.auto.DirectApps
    when {
        direct.displayId < 0 -> ui.toast(t("{0} açılamadı", name))
        direct.ownRunning() -> launchScope.launch {
            if (direct.open(ctx, pkg)) ui.toast(t("{0} araç ekranında açıldı. NewREX'e dönmek için ⌂", name))
            else ui.toast(t("{0} araç ekranında açılmayı desteklemiyor.", name), long = true)
        }
        direct.running() -> launchScope.launch {
            if (!direct.granted()) ui.toast(t("Telefonunda Shizuku izni soruluyor; izin verirsen uygulamalar yansıtmasız açılır."), long = true)
            when {
                !direct.requestPermission() -> ui.toast(t("Shizuku izni verilmedi."))
                direct.open(ctx, pkg) -> ui.toast(t("{0} araç ekranında açıldı. NewREX'e dönmek için ⌂", name))
                else -> ui.toast(t("{0} araç ekranında açılmayı desteklemiyor.", name), long = true)
            }
        }
        else -> launchScope.launch {
            // NewREX'in yardımcısı bu açılışta başlatıldıysa birkaç saniye içinde bağlanır
            if (com.newrex.auto.SelfAdb.expected(ctx) && direct.awaitOwn(3500)) {
                if (direct.open(ctx, pkg)) ui.toast(t("{0} araç ekranında açıldı. NewREX'e dönmek için ⌂", name))
                else ui.toast(t("{0} araç ekranında açılmayı desteklemiyor.", name), long = true)
                return@launch
            }
            // Uzun açıklama 10 dakikada bir; arada kısa uyarı (araçta her dokunuşta aynı yazı çıkıp durmasın)
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - shizukuWarned > 10 * 60_000L) {
                shizukuWarned = now
                ui.toast(if (com.newrex.auto.SelfAdb.paired(ctx))
                    t("Telefon yeniden başladıktan sonra henüz Wi-Fi'ye bağlanmadı. Wi-Fi'ye bağlanınca uygulama açma kendiliğinden hazırlanır.")
                    else t("Telefon uygulamalarını araçta açmak için telefonda NewREX → Kurulum adımlarını bir kez yap."), long = true)
            } else ui.toast(t("{0} açılamadı", name))
        }
    }
}

