// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.data.Maintenance
import com.newrex.auto.data.ParkedSpot
import com.newrex.auto.data.Playlist
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.rounded

/** Telefonda açılan ekran: IPTV listelerini yönetme, izinler ve kurulum rehberi. */
class MainActivity : Activity() {
    private lateinit var playlistBox: LinearLayout
    private lateinit var locationStatus: TextView
    private lateinit var locationButton: Button
    private lateinit var mirrorStatus: TextView
    private lateinit var mirrorButton: Button
    private lateinit var touchStatus: TextView
    private lateinit var touchButton: Button
    private lateinit var parkStatus: TextView
    private lateinit var parkButtons: LinearLayout
    private lateinit var maintenanceBox: LinearLayout
    private val tr = java.util.Locale("tr", "TR")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(32))
        }

        col.addView(label("NewREX", 34f, bold = true))
        col.addView(label("Android Auto  •  Geliştirici: NewREX", 14f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(4), b = dp(18)))

        // Park modu için konum izni
        locationStatus = label("", 14f, Palette.TEXT_DIM, lines = 4)
        locationButton = button("Konum iznini ver") {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        col.addView(card("Park modu", locationStatus, locationButton))

        // Ekran yansıtma
        mirrorStatus = label("", 14f, Palette.TEXT_DIM, lines = 4)
        mirrorButton = button("Ekranı arabaya yansıt") {
            if (Mirror.active.value) { Mirror.stop(this); mirrorStatus.postDelayed({ refreshMirror() }, 600); return@button }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            Mirror.requestStart(this)
        }
        touchStatus = label("", 14f, Palette.TEXT_DIM, lines = 8)
        touchButton = button("Dokunma iznini aç") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        col.addView(card("Ekran yansıtma", mirrorStatus, mirrorButton, touchStatus, touchButton,
            button("İzin gri görünüyorsa: uygulama bilgisi") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }))

        // Arabam nerede?
        parkStatus = label("", 14f, Palette.TEXT_DIM, lines = 4)
        parkButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card("Arabam nerede?", parkStatus, parkButtons))

        // Bakım hatırlatıcı
        maintenanceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card("Bakım hatırlatıcı",
            label("Tarihe dokunup seç. Zamanı yaklaşınca (15 gün kala) araç ekranında uyarı çıkar.", 14f, Palette.TEXT_DIM, lines = 3),
            maintenanceBox))

        // IPTV listeleri
        playlistBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(card("IPTV listeleri",
            label("M3U / M3U8 bağlantısı ya da telefondaki bir liste dosyası ekleyebilirsin.", 14f, Palette.TEXT_DIM, lines = 3),
            playlistBox,
            button("Bağlantı ile liste ekle") { addPlaylistDialog() },
            button("Telefondan dosya seç") {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PICK_FILE)
            },
        ))

        col.addView(card("Arabada nasıl açılır?", label(
            "1. Telefonu araca bağla, Android Auto açılsın.\n" +
                "2. Araç ekranında uygulamalar menüsüne gir.\n" +
                "3. \"NewREX Android Auto\" simgesine dokun.\n\n" +
                "Görünmüyorsa: Android Auto ayarları → Geliştirici ayarları → \"Bilinmeyen kaynaklar\" açık olmalı.",
            14f, Palette.TEXT_DIM, lines = 10)))

        col.addView(card("Hakkında", label(
            "NewREX Android Auto ${BuildConfig.VERSION_NAME}\n" +
                "Orijinal geliştirici: ${Brand.ORIGINAL_DEVELOPER}\n" +
                "Reklam yok, takip yok, hiçbir veri toplanmaz. Ayarların ve favorilerin sadece bu telefonda durur.\n\n" +
                "${Brand.COPYRIGHT}\n${Brand.LICENSE_NOTICE}",
            14f, Palette.TEXT_DIM, lines = 12)))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG_TOP)
            // Android 15 içeriği durum çubuğunun altına kadar uzatıyor; başlık saatin altına girmesin
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
            addView(col)
        })
    }

    override fun onResume() {
        super.onResume()
        refreshLocation()
        refreshPlaylists()
        refreshMirror()
        refreshPark()
        refreshMaintenance()
        // Yansıtma servisi onaydan hemen sonra başlıyor; durumu biraz sonra tekrar oku
        mirrorStatus.postDelayed({ refreshMirror() }, 900)
    }

    private fun refreshMirror() {
        val on = Mirror.active.value
        mirrorStatus.text = if (on) "✅ Ekran araca yansıtılıyor. Araçta NewREX → Yansıt'a gir."
        else "Telefonundaki her şeyi (oyun, sosyal medya, uygulamalar) araç ekranında göster. Her başlatmada telefonda bir kez \"Başlat\" onayı istenir. " +
            "Netflix gibi korumalı uygulamalar siyah görünür. Yansıtma sırasında telefon ekranı açık kalır."
        mirrorButton.text = if (on) "Yansıtmayı durdur" else "Ekranı arabaya yansıt"
        val touch = Mirror.touchEnabled(this)
        touchStatus.text = if (touch) "✅ Dokunma açık: araç ekranından telefona dokunabilirsin."
        else "Araç ekranından telefona dokunabilmek için Erişilebilirlik izni gerekir: \"Dokunma iznini aç\" → " +
            "\"Yüklü uygulamalar\" → \"NewREX ekran yansıtma dokunması\" → Aç.\n" +
            "Bu izin ekranındaki yazıları OKUMAZ, hiçbir şeyi kaydetmez; sadece dokunma ve geri/ana ekran tuşu gönderir.\n" +
            "Ayar gri ve açılmıyorsa: uygulama bilgisi → sağ üst ⋮ → \"Kısıtlanmış ayarlara izin ver\"."
        touchButton.visibility = if (touch) View.GONE else View.VISIBLE
    }

    private fun refreshPark() {
        parkButtons.removeAllViews()
        val spot = Prefs.parkedSpot
        if (spot == null) {
            parkStatus.text = "Araç kapanınca (Android Auto bağlantısı kesilince) bulunduğun yer otomatik kaydedilir. " +
                "Henüz kayıtlı park yeri yok."
        } else {
            val time = java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", tr).format(java.util.Date(spot.time))
            parkStatus.text = "🅿️ Son park: $time\nKonum hassasiyeti: ~${spot.accuracy.toInt()} m"
            parkButtons.addView(button("Haritada göster") { openMap(spot, walk = false) }, lp(MATCH, WRAP).margins(t = dp(8)))
            parkButtons.addView(button("Yürüyerek yol tarifi") { openMap(spot, walk = true) }, lp(MATCH, WRAP).margins(t = dp(8)))
            parkButtons.addView(button("Park yerini sil") { Prefs.parkedSpot = null; refreshPark() }, lp(MATCH, WRAP).margins(t = dp(8)))
        }
        parkButtons.addView(button("Şu anki yerimi park yeri olarak kaydet") {
            val loc = DriveState.bestLocation(this)
            if (loc == null) {
                AlertDialog.Builder(this).setMessage("Konum alınamadı. Konum iznini ver ve konumu aç.").setPositiveButton("Tamam", null).show()
            } else {
                Prefs.parkedSpot = ParkedSpot(loc.latitude, loc.longitude, System.currentTimeMillis(), loc.accuracy)
                refreshPark()
            }
        }, lp(MATCH, WRAP).margins(t = dp(8)))
    }

    private fun openMap(spot: ParkedSpot, walk: Boolean) {
        val ll = "${spot.lat},${spot.lon}"
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll&mode=w"))
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll(Arabam)"))
        runCatching { startActivity(if (walk) nav else geo) }.onFailure { runCatching { startActivity(geo) } }
    }

    private fun refreshMaintenance() {
        maintenanceBox.removeAllViews()
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", tr)
        Maintenance.values().forEach { m ->
            val day = Prefs.maintenanceDay(m)?.let { java.time.LocalDate.ofEpochDay(it) }
            val left = day?.let { it.toEpochDay() - today.toEpochDay() }
            val (status, color) = when {
                left == null -> "Tarih girilmedi" to Palette.TEXT_FAINT
                left < 0 -> "${fmt.format(day)} • ${-left} gün gecikti" to Palette.RED
                left <= 15 -> "${fmt.format(day)} • $left gün kaldı" to Palette.ORANGE
                else -> "${fmt.format(day)} • $left gün kaldı" to Palette.GREEN
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(6), dp(10))
                background = rounded(0x14FFFFFF, dpf(12))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label("${m.emoji}  ${m.title}", 15f, bold = true))
                    addView(label(status, 12f, color), lp(MATCH, WRAP).margins(t = dp(3)))
                }, lp(0, WRAP, 1f))
                if (day != null) addView(Button(this@MainActivity).apply {
                    text = "Sil"
                    setOnClickListener { Prefs.setMaintenanceDay(m, null); refreshMaintenance() }
                })
                setOnClickListener {
                    val start = day ?: today.plusMonths(m.months.toLong())
                    DatePickerDialog(this@MainActivity, { _, y, mo, d ->
                        Prefs.setMaintenanceDay(m, java.time.LocalDate.of(y, mo + 1, d).toEpochDay())
                        refreshMaintenance()
                    }, start.year, start.monthValue - 1, start.dayOfMonth).show()
                }
            }
            maintenanceBox.addView(row, lp(MATCH, WRAP).margins(t = dp(8)))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        refreshLocation()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        // Araç tarafı dosyayı daha sonra da okuyabilsin
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Liste dosyası"
        Prefs.playlists = Prefs.playlists + Playlist(name.substringBeforeLast('.'), uri.toString())
        refreshPlaylists()
    }

    private fun refreshLocation() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationStatus.text = if (granted)
            "✅ Konum izni verildi. Araç hareket edince video otomatik gizlenir, ses devam eder."
        else
            "Araç hız bilgisi vermezse hareketi telefonun GPS'inden algılarız. Bunun için konum izni gerekiyor."
        locationButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun refreshPlaylists() {
        playlistBox.removeAllViews()
        val lists = Prefs.playlists
        if (lists.isEmpty()) {
            playlistBox.addView(label("Henüz liste yok.", 14f, Palette.TEXT_FAINT), lp(MATCH, WRAP).margins(t = dp(10)))
        }
        lists.forEachIndexed { i, pl ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(6), dp(10))
                background = rounded(0x14FFFFFF, dpf(12))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(pl.name, 15f, bold = true))
                    addView(label(pl.url, 11f, Palette.TEXT_FAINT), lp(MATCH, WRAP).margins(t = dp(2)))
                }, lp(0, WRAP, 1f))
                addView(Button(this@MainActivity).apply {
                    text = "Sil"
                    setOnClickListener {
                        Prefs.playlists = Prefs.playlists.filterIndexed { j, _ -> j != i }
                        refreshPlaylists()
                    }
                })
            }
            playlistBox.addView(row, lp(MATCH, WRAP).margins(t = dp(10)))
        }
    }

    private fun addPlaylistDialog() {
        val name = EditText(this).apply { hint = "Liste adı (ör. Evdeki liste)"; setSingleLine() }
        val url = EditText(this).apply {
            hint = "https://…/liste.m3u"
            setSingleLine()
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(name)
            addView(url)
        }
        AlertDialog.Builder(this)
            .setTitle("IPTV listesi ekle")
            .setView(box)
            .setPositiveButton("Ekle") { _, _ ->
                val u = url.text.toString().trim()
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    val n = name.text.toString().trim().ifEmpty { "Liste ${Prefs.playlists.size + 1}" }
                    Prefs.playlists = Prefs.playlists + Playlist(n, u)
                    refreshPlaylists()
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun card(title: String, vararg body: View): View = panel().apply {
        setPadding(dp(16), dp(14), dp(16), dp(16))
        addView(label(title, 18f, bold = true), lp(MATCH, WRAP).margins(b = dp(8)))
        body.forEach { addView(it, lp(MATCH, WRAP).margins(t = dp(6))) }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(14))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Palette.accents[0], dpf(12))
        setOnClickListener { onClick() }
    }

    private companion object {
        const val PICK_FILE = 7
    }
}
