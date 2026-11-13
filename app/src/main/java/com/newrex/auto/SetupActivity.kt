// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Kurulum: NewREX'in istediği her izin ve ayar sırayla, tek ekranda. Her adımda durum (tamam / yapılacak) ve
 * o adımı yapan tek bir düğme var. Ekrana her dönüldüğünde durumlar yeniden okunur.
 */
class SetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var list: LinearLayout
    private lateinit var progress: TextView

    private class Step(
        val title: String,
        val why: String,
        val done: () -> Boolean,
        val button: String,
        /** Bu adım olmadan da NewREX çalışır (isteğe bağlı). */
        val optional: Boolean = false,
        /** Durumu telefondan okunamıyor; kullanıcı kendisi bakar. */
        val manual: Boolean = false,
        val action: () -> Unit,
    )

    private fun granted(vararg p: String) = p.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun steps(): List<Step> = listOf(
        Step(t("Bildirim izni"), t("Eşleştirme kodu ve yolculuk takibi bildirimle gösterilir."),
            { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }, t("İzin ver")) {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        },
        Step(t("Konum izni"), t("Hız, yolculuk, park yeri ve harita için."),
            { granted(Manifest.permission.ACCESS_FINE_LOCATION) }, t("İzin ver")) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 2)
        },
        Step(t("Medya ve mikrofon izni"), t("Telefondaki müzik ve videolar ile sesli komut için. Dosyalar telefondan çıkmaz."),
            {
                if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.RECORD_AUDIO)
                else granted(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
            }, t("İzin ver"), optional = true) {
            requestPermissions(if (Build.VERSION.SDK_INT >= 33)
                arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.RECORD_AUDIO)
                else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO), 3)
        },
        Step(t("Pil kısıtlamasını kaldır"), t("Telefon NewREX'i arka planda kapatmasın; yolculuk ve km sayımı kesilmesin."),
            { (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName) }, t("Kaldır")) {
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        },
        Step(t("Arabada görünür yap"), t("Android Auto NewREX'i tanısın ve sürüşte de açılsın."),
            { BuildConfig.PARK_ONLY || CarEntry.trustedInstall(this) }, t("Yap")) {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_FIX_INSTALL, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        },
        Step(t("Android Auto: bilinmeyen kaynaklar"),
            t("Arabada NewREX görünmüyorsa: Android Auto ayarları → en alttaki Sürüm'e 10 kez dokun → sağ üstteki ⋮ → Geliştirici ayarları → Bilinmeyen kaynaklar'ı aç."),
            { false }, t("Android Auto ayarlarını aç"), optional = true, manual = true) {
            runCatching {
                startActivity(Intent().setClassName("com.google.android.projection.gearhead",
                    "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
        Step(t("Geliştirici seçenekleri"),
            t("Telefon uygulamalarını araçta açmak için gerekli. Ayarlar → Telefon hakkında → Yazılım bilgileri → Yapım numarası'na 7 kez dokun."),
            { Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }, t("Telefon hakkında'yı aç"), optional = true) {
            runCatching { startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
        },
        Step(t("Eşleştirme (bir kez)"),
            t("Wi-Fi'de ol. Açılan sayfada “Kablosuz hata ayıklama”ya gir ve “Eşleme kodu ile cihaz eşle”ye bas. Ekranın üstünde NewREX'in kod kutusu kendiliğinden çıkar: gördüğün 6 rakamı yazıp gönder. Telefon başına bir kez."),
            { SelfAdb.paired(this) }, t("Eşleştir"), optional = true) {
            SelfAdb.beginPairing(this)
        },
        Step(t("Uygulamaları araçta açma"),
            t("Yardımcı başlar ve kendine otomatik başlama iznini verir. Sonra telefon her açılıp Wi-Fi'ye bağlandığında kendiliğinden hazırlanır; Shizuku gerekmez."),
            { DirectApps.ownRunning() && SelfAdb.canToggle(this) }, t("Başlat"), optional = true) {
            scope.launch {
                toast(t("Başlatılıyor…"))
                val ok = SelfAdb.start(this@SetupActivity)
                toast(if (ok) t("Hazır.") else t("Başlamadı. Wi-Fi'de misin ve eşleştirme yapıldı mı?"))
                refresh()
            }
        },
    )

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.newrex.auto.data.Board.init(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        progress = label("", 14f, Palette.TEXT_DIM, lines = 3)
        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0B0D12.toInt())
            fitsSystemWindows = true
            addView(LinearLayout(this@SetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(22), dp(18), dp(24))
                addView(label(t("Kurulum"), 26f, bold = true))
                addView(progress, lp(MATCH, WRAP).margins(t = dp(4), b = dp(14)))
                addView(list, lp(MATCH, WRAP))
            })
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val all = steps()
        val required = all.filter { !it.optional && !it.manual }
        progress.text = t("{0} / {1} zorunlu adım tamam. İsteğe bağlı adımlar telefon uygulamalarını araçta açmak içindir.",
            required.count { it.done() }, required.size)
        var n = 0
        all.forEach { s ->
            n++
            val ok = !s.manual && s.done()
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xE6121419.toInt(), dpf(18), dp(1), if (ok) Palette.withAlpha(0xFF16B98A.toInt(), 0x66) else 0x1FFFFFFF)
                setPadding(dp(16), dp(14), dp(16), dp(14))
                addView(LinearLayout(this@SetupActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(if (ok) "✓" else "$n", 15f, if (ok) 0xFF16B98A.toInt() else Palette.TEXT, bold = true).apply {
                        gravity = Gravity.CENTER
                        background = rounded(if (ok) Palette.withAlpha(0xFF16B98A.toInt(), 0x33) else 0x22FFFFFF, dpf(15))
                    }, lp(dp(30), dp(30)).margins(r = dp(12)))
                    addView(label(s.title, 17f, bold = true, lines = 2), lp(0, WRAP, 1f))
                    if (s.optional && !ok) addView(label(t("İsteğe bağlı"), 12f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(l = dp(8)))
                })
                addView(label(s.why, 13f, Palette.TEXT_DIM, lines = 8), lp(MATCH, WRAP).margins(t = dp(8)))
                if (!ok) addView(pill(s.button, heightDp = 44, selected = !s.optional) { s.action() },
                    lp(WRAP, dp(44)).margins(t = dp(10)))
            }, lp(MATCH, WRAP).margins(b = dp(10)))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) = c.startActivity(Intent(c, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
