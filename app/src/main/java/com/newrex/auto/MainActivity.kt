// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.animation.LayoutTransition
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import com.newrex.auto.data.Board
import com.newrex.auto.data.CarLog
import com.newrex.auto.data.FuelEntry
import com.newrex.auto.data.Maintenance
import com.newrex.auto.data.ParkedSpot
import com.newrex.auto.data.Playlist
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.ClockView
import com.newrex.auto.ui.ColorPickerView
import com.newrex.auto.ui.FmDisplayView
import com.newrex.auto.ui.WaveBackground
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.neonText
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Telefonda açılan ekran: fotoğraflar, listeler, izinler ve yardım. Bölümler dokununca açılır. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wave: WaveBackground
    private lateinit var logo: TextView
    private lateinit var themeSummary: TextView
    private lateinit var looksSummary: TextView
    private lateinit var locationCard: View
    private lateinit var locationStatus: TextView
    private lateinit var mediaCard: View
    private lateinit var photosBox: LinearLayout
    private lateinit var photosSummary: TextView
    private lateinit var mirrorStatus: TextView
    private lateinit var selfStatus: TextView
    private lateinit var selfSummary: TextView
    private lateinit var mirrorButton: Button
    private lateinit var mirrorSummary: TextView
    private lateinit var parkStatus: TextView
    private lateinit var parkButtons: LinearLayout
    private lateinit var parkSummary: TextView
    private lateinit var maintenanceBox: LinearLayout
    private lateinit var maintenanceSummary: TextView
    private lateinit var playlistBox: LinearLayout
    private lateinit var carBox: LinearLayout
    private lateinit var carSummary: TextView
    private lateinit var playlistSummary: TextView
    private val tr = I18n.locale()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Board.init(this)
        // Uygulama yansıtma sırasında kapandıysa telefon yan kalmasın
        com.newrex.auto.PhoneRotation.restoreIfStale(this)
        CarLog.init(this)
        Palette.load()
        CarEntry.apply(this)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(32))
            layoutTransition = LayoutTransition()
        }

        logo = label("", 36f, bold = true)
        col.addView(logo)
        paintLogo()
        col.addView(label(t("Android Auto için TV, YouTube, radyo ve daha fazlası"), 14f, Palette.TEXT_DIM, lines = 2),
            lp(MATCH, WRAP).margins(t = dp(4), b = dp(20)))

        // Kurulum: bütün izinler ve ayarlar sırayla, tek ekranda
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Palette.withAlpha(Palette.accent, 0x40), dpf(18), dp(1), Palette.withAlpha(Palette.accent, 0xAA))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(t("Kurulum"), 17f, bold = true))
                addView(label(t("Gerekli izinleri ve ayarları adım adım yap"), 13f, Palette.TEXT_DIM, lines = 2), lp(MATCH, WRAP).margins(t = dp(3)))
            }, lp(0, WRAP, 1f))
            addView(icon(R.drawable.ic_chevron_right, 22, Palette.TEXT), lp(dp(22), dp(22)))
            setOnClickListener { SetupActivity.start(this@MainActivity) }
        }, lp(MATCH, WRAP).margins(b = dp(10)))

        // Dil seçimi: başlık iki dilli kalır ki yanlış dil seçen kişi de geri bulabilsin
        val langSummary = summary().apply { text = langLabel(I18n.choice(this@MainActivity)) }
        col.addView(section(R.drawable.ic_language, 0xFF6366F1.toInt(), "🌐 " + t("Dil") + if (I18n.code != "en") " · Language" else "",
            langSummary, button(t("Dili değiştir")) { pickLanguage() }))

        // Konum izni yoksa en üstte tek bir uyarı; izin verilince kaybolur
        locationStatus = label(t("Araç hız bilgisi vermezse hareketi telefonun GPS'inden algılarız (park modu, park yeri, hava durumu)."),
            14f, Palette.TEXT_DIM, lines = 4)
        locationCard = panel().apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(0xF0241A12.toInt(), dpf(18), dp(1), Palette.withAlpha(Palette.ORANGE, 0x88))
            addView(label(t("📍 Konum izni gerekli"), 16f, bold = true))
            addView(locationStatus, lp(MATCH, WRAP).margins(t = dp(6)))
            addView(button(t("Konum iznini ver")) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
            }, lp(MATCH, WRAP).margins(t = dp(10)))
        }
        col.addView(locationCard, lp(MATCH, WRAP).margins(b = dp(12)))

        // Araç ekranı izin penceresi gösteremiyor; müzik/video listesi ve sesli komut için izin telefondan verilir
        mediaCard = panel().apply {
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = rounded(0xF0241A12.toInt(), dpf(18), dp(1), Palette.withAlpha(Palette.ORANGE, 0x88))
            addView(label(t("🎵 Medya ve mikrofon izni"), 16f, bold = true))
            addView(label(t("Araçta \"Medya\" sayfasında telefondaki müzik ve videoları görmek ve sesli komut kullanmak için gerekli. ") +
                t("Dosyalar telefondan dışarı gönderilmez."), 14f, Palette.TEXT_DIM, lines = 4), lp(MATCH, WRAP).margins(t = dp(6)))
            addView(button(t("İzinleri ver")) { requestPermissions(missingMediaPermissions(), 4) }, lp(MATCH, WRAP).margins(t = dp(10)))
        }
        col.addView(mediaCard, lp(MATCH, WRAP).margins(b = dp(12)))

        photosBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        photosSummary = summary()
        col.addView(section(R.drawable.ic_photo, 0xFFE040FB.toInt(), t("Araç ekranı fotoğrafları"), photosSummary,
            label(t("Araçta açılışta saatin yanında görünür. Araç ekranında ✎ ile taşıyabilir, büyütebilir veya kaldırabilirsin. ") +
                t("Fotoğraflar sadece bu telefonda saklanır."), 14f, Palette.TEXT_DIM, lines = 5),
            photosBox,
            button(t("Fotoğraf ekle")) { PhotoPickActivity.start(this) }))

        themeSummary = summary()
        col.addView(section(R.drawable.ic_settings, 0xFF00BCD4.toInt(), t("Tema"), themeSummary, *themeBody()))

        // Araç ekranının arka plan fotoğrafı
        val bgSummary = summary().apply {
            text = if (com.newrex.auto.data.Background.has(this@MainActivity)) t("Fotoğraf seçili") else t("Fotoğraf yok")
        }
        col.addView(section(R.drawable.ic_photo, 0xFF8B5CF6.toInt(), t("Arka plan fotoğrafı"), bgSummary,
            label(t("En iyi sonuç: yatay, en az 1920×720 (geniş ekran) ya da 1920×1080 fotoğraf. Fotoğraf ekranı dolduracak şekilde ortadan kırpılır; önemli kısmı ortada olsun. Koyu ve sade fotoğraflarda yazılar daha iyi okunur."),
                13f, Palette.TEXT_DIM, lines = 6),
            button(t("Telefondan seç")) { PhotoPickActivity.startBackground(this) },
            button(t("Kaldır")) { com.newrex.auto.data.Background.remove(this); bgSummary.text = t("Fotoğraf yok") }))

        // "Hey <kelime>" uyandırma sözü
        val wakeSummary = summary().apply { text = if (Prefs.wakeEnabled) "Hey ${com.newrex.auto.WakeWord.word}" else t("Kapalı") }
        val wakeField = EditText(this).apply {
            setSingleLine()
            setText(Prefs.wakeWord)
            hint = "newrex"
            setTextColor(Palette.TEXT)
        }
        col.addView(section(R.drawable.ic_mic, 0xFF0891B2.toInt(), t("Sesli uyandırma"), wakeSummary,
            label(t("Arabada \"Hey\" ve aşağıya yazdığın kelimeyi söyleyince NewREX \"Sizi dinliyorum\" der ve komutunu bekler (ör. hey newrex, hey maleren)."),
                13f, Palette.TEXT_DIM, lines = 4),
            wakeField,
            button(t("Kaydet")) {
                val w = wakeField.text.toString().trim().lowercase(I18n.locale())
                if (w.isNotEmpty()) Prefs.wakeWord = w
                Prefs.wakeEnabled = true
                com.newrex.auto.WakeWord.settingsChanged()
                wakeSummary.text = "Hey ${com.newrex.auto.WakeWord.word}"
            },
            button(t("Kapat")) { Prefs.wakeEnabled = false; com.newrex.auto.WakeWord.settingsChanged(); wakeSummary.text = t("Kapalı") }))

        val qualitySummary = summary()
        col.addView(section(R.drawable.ic_live_tv, 0xFF06B6D4.toInt(), t("TV görüntü kalitesi"), qualitySummary,
            choiceButtons(PlayerHub.QUALITIES.map { it.first }, { Prefs.tvQuality }, { Prefs.tvQuality = it }, qualitySummary,
                t("Otomatik: yayının en iyi kalitesi. Düşük seçersen telefon daha az ısınır, daha az internet harcar."))))

        val saverSummary = summary()
        col.addView(section(R.drawable.ic_speed, 0xFF16B98A.toInt(), t("Tasarruf modu"), saverSummary, saverBody(saverSummary)))

        looksSummary = summary()
        col.addView(section(R.drawable.ic_radio, 0xFFFFB238.toInt(), t("Saat ve radyo görünümü"), looksSummary, *looksBody()))

        mirrorStatus = label("", 14f, Palette.TEXT_DIM, lines = 6)
        mirrorButton = button(t("Ekranı arabaya yansıt")) {
            if (Mirror.active.value) { Mirror.stop(this); mirrorStatus.postDelayed({ refreshMirror() }, 600); return@button }
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            Mirror.requestStart(this)
        }
        mirrorSummary = summary()
        col.addView(section(R.drawable.ic_cast, 0xFF16B98A.toInt(), t("Ekran yansıtma"), mirrorSummary, mirrorStatus, mirrorButton))

        // Telefon uygulamalarını araç ekranında açma: NewREX'in kendi yardımcısı (Shizuku gerekmez)
        selfStatus = label("", 14f, Palette.TEXT_DIM, lines = 8)
        selfSummary = summary()
        col.addView(section(R.drawable.ic_apps, 0xFF2F80ED.toInt(), t("Uygulamaları araçta açma"), selfSummary,
            label(t("Spotify, WhatsApp gibi telefon uygulamaları araç ekranında doğrudan açılır, telefon kilitliyken de çalışır. Bir kez eşleştirme yeter; sonra telefon her açılıp Wi-Fi'ye bağlandığında NewREX bunu kendiliğinden hazırlar. Bağlantı telefonun içinde kalır, hiçbir yere bir şey gönderilmez."),
                13f, Palette.TEXT_DIM, lines = 8),
            selfStatus,
            button(t("Eşleştir (bir kez)")) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
                SelfAdb.beginPairing(this)
            },
            button(t("Şimdi başlat")) {
                selfStatus.text = t("Başlatılıyor…")
                scope.launch {
                    val ok = SelfAdb.start(this@MainActivity)
                    refreshSelf()
                    if (!ok) selfStatus.text = t("Başlamadı. Telefon Wi-Fi'ye bağlı mı ve eşleştirme yapıldı mı, kontrol et.")
                }
            }))

        parkStatus = label("", 14f, Palette.TEXT_DIM, lines = 4)
        parkButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parkSummary = summary()
        col.addView(section(R.drawable.ic_parking, 0xFF2F80ED.toInt(), t("Arabam nerede?"), parkSummary, parkStatus, parkButtons))

        // Harita · Trafik: kullanıcının kendi ücretsiz TomTom anahtarı
        val ttSummary = summary().apply { text = if (com.newrex.auto.data.TomTom.has) t("Trafik açık") else t("Anahtar yok (trafiksiz)") }
        val ttField = EditText(this).apply {
            setSingleLine()
            setText(Prefs.tomtomKey)
            hint = t("TomTom anahtarını buraya yapıştır")
            setTextColor(Palette.TEXT)
        }
        val ttStatus = label("", 14f, Palette.TEXT_DIM, lines = 3)
        col.addView(section(R.drawable.ic_place, 0xFF0EA5E9.toInt(), t("Harita · Trafik"), ttSummary,
            label(t("İsteğe bağlı. Kendi ücretsiz TomTom anahtarını girersen araç ekranındaki haritada canlı trafik renkleri, trafiğe göre rota ve gerçek varış süresi görünür. Girmezsen harita ve rota yine çalışır, sadece trafik yoktur. Anahtar sadece bu telefonda saklanır; rota için başlangıç ve hedef noktası TomTom'a gönderilir."),
                13f, Palette.TEXT_DIM, lines = 6),
            label(t("Nasıl alınır (3-5 dakika, ücretsiz, kredi kartı yok):\n1. \"Ücretsiz anahtar al\"a bas, e-postanla kayıt ol.\n2. Gelen e-postadaki onay linkine tıkla.\n3. Giriş yapınca \"Keys\" bölümündeki hazır anahtarı kopyala.\n4. Aşağıya yapıştırıp \"Kaydet ve dene\"ye bas."),
                13f, Palette.TEXT, lines = 8),
            button(t("Ücretsiz anahtar al")) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(com.newrex.auto.data.TomTom.SIGNUP_URL))) }
            },
            ttField,
            button(t("Kaydet ve dene")) {
                val k = com.newrex.auto.data.TomTom.clean(ttField.text.toString())
                ttField.setText(k)
                if (k.length < 20) { ttStatus.text = t("Anahtar çok kısa, tamamını kopyaladığından emin ol."); return@button }
                ttStatus.text = t("Deneniyor…")
                Thread {
                    val ok = com.newrex.auto.data.TomTom.test(k)
                    runOnUiThread {
                        when (ok) {
                            true -> {
                                Prefs.tomtomKey = k
                                ttStatus.text = t("Çalışıyor ✓ Araç ekranında haritayı yeniden açınca trafik görünür.")
                                ttSummary.text = t("Trafik açık")
                            }
                            false -> ttStatus.text = t("Anahtar hatalı ya da henüz etkin değil. E-postayı onayladın mı?")
                            null -> ttStatus.text = t("İnternete bağlanılamadı, tekrar dene.")
                        }
                    }
                }.start()
            },
            ttStatus,
            button(t("Kaldır")) {
                Prefs.tomtomKey = ""; ttField.setText("")
                ttStatus.text = ""; ttSummary.text = t("Anahtar yok (trafiksiz)")
            }))

        // OBD-II adaptörü (ELM327 Bluetooth)
        obdSummary = summary().apply { text = obdText() }
        col.addView(section(R.drawable.ic_car, 0xFFEF4444.toInt(), t("OBD-II adaptör"), obdSummary,
            label(t("Arabanın OBD soketine (genelde direksiyonun altında) takılan Bluetooth ELM327 adaptörüyle motor devri, motor sıcaklığı, akü voltajı, yakıt ve arıza kodları araç ekranındaki Araç sayfasında görünür. Önce adaptörü telefonun Bluetooth ayarlarından eşleştir (şifre genelde 1234 ya da 0000), sonra burada seç. Veriler telefonda kalır."),
                13f, Palette.TEXT_DIM, lines = 8),
            button(t("Adaptör seç")) { pickObd() },
            button(t("Kaldır")) {
                Prefs.obdMac = ""; Prefs.obdName = ""
                com.newrex.auto.data.Obd.restart(this)
                obdSummary.text = obdText()
            }))

        maintenanceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        maintenanceSummary = summary()
        col.addView(section(R.drawable.ic_build, Palette.ORANGE, t("Bakım hatırlatıcı"), maintenanceSummary,
            label(t("Tarihe dokunup seç. 15 gün kala araç ekranında uyarı çıkar."), 14f, Palette.TEXT_DIM, lines = 3),
            maintenanceBox))

        carBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        carSummary = summary()
        col.addView(section(R.drawable.ic_gas, 0xFF16B98A.toInt(), t("Yakıt ve kilometre"), carSummary, carBox))

        playlistBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playlistSummary = summary()
        col.addView(section(R.drawable.ic_live_tv, 0xFF7C5CFF.toInt(), t("IPTV listeleri"), playlistSummary,
            label(t("M3U / M3U8 bağlantısı ya da telefondaki bir liste dosyası ekleyebilirsin."), 14f, Palette.TEXT_DIM, lines = 3),
            playlistBox,
            button(t("Bağlantı ile liste ekle")) { addPlaylistDialog() },
            button(t("Telefondan dosya seç")) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PICK_FILE)
            },
        ))

        col.addView(section(R.drawable.ic_info, 0xFF8B93A7.toInt(), t("Yardım"), summary().apply { text = t("Arabada görünmüyorsa buraya bak") },
            label(t("1. Telefonu araca bağla, Android Auto açılsın.\n") +
                t("2. Araç ekranında uygulamalar menüsüne gir.\n") +
                t("3. \"NewREX Android Auto\" simgesine dokun.\n\n") +
                t("Görünmüyorsa: Android Auto ayarları → Geliştirici ayarları → \"Bilinmeyen kaynaklar\" açık olmalı."),
                14f, Palette.TEXT_DIM, lines = 10),
            label(t("Sorun giderme"), 15f, bold = true),
            label(verdict(), 14f, Palette.TEXT, lines = 8),
            fixRow(),
            entryRow(),
            button(t("Pil kısıtlamasını kaldır")) { askBatteryExemption() },
            label(diagnostics(), 13f, Palette.TEXT_DIM, lines = 20)))

        col.addView(label(
            t("NewREX Android Auto {0} • Orijinal geliştirici: {1}\n", BuildConfig.VERSION_NAME, Brand.ORIGINAL_DEVELOPER) +
                t("Reklam yok, takip yok, hiçbir veri toplanmaz.\n\n{0}\n{1}", Brand.COPYRIGHT, Brand.LICENSE_NOTICE),
            12f, Palette.TEXT_FAINT, lines = 10).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(18)))

        wave = WaveBackground(this)
        paintWave(animate = false)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Palette.BG_TOP)
            addView(wave, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(ScrollView(this@MainActivity).apply {
                isVerticalScrollBarEnabled = false
                // Android 15 içeriği durum çubuğunun altına kadar uzatıyor; başlık saatin altına girmesin
                setOnApplyWindowInsetsListener { v, insets ->
                    // Eski Android'lerde (8–10) yeni insets çağrısı uygulamayı çökertiyordu
                    val bars = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets, v)
                        .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    v.setPadding(0, bars.top, 0, bars.bottom)
                    insets
                }
                addView(col)
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        })

        scope.launch { Board.photos.collect { refreshPhotos() } }
        scope.launch { CarLog.changes.collect { refreshCar() } }
        val untrusted = !BuildConfig.PARK_ONLY && !CarEntry.trustedInstall(this)
        if (untrusted && Prefs.selfInstallTried == 0) showDrivingGuide()
        else if (untrusted && Prefs.installFailShown != BuildConfig.VERSION_CODE) showInstallFailed()
        else scope.launch { Updates.check(this@MainActivity)?.let { Updates.show(this@MainActivity, it) } }
        if (intent?.getBooleanExtra(EXTRA_ASK_LOCATION, false) == true) askLocationNow()
        if (intent?.getBooleanExtra(EXTRA_FIX_INSTALL, false) == true) fixInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_ASK_LOCATION, false)) askLocationNow()
        if (intent.getBooleanExtra(EXTRA_FIX_INSTALL, false)) fixInstall()
    }

    private fun askLocationNow() {
        if (!DriveState.hasLocationPermission(this))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
    }

    override fun onResume() {
        super.onResume()
        wave.running = true
        refreshLocation()
        refreshPlaylists()
        refreshMirror()
        refreshSelf()
        refreshPark()
        refreshMaintenance()
        // Yansıtma servisi onaydan hemen sonra başlıyor; durumu biraz sonra tekrar oku
        mirrorStatus.postDelayed({ refreshMirror() }, 900)
    }

    override fun onPause() {
        wave.running = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Başlığa dokununca açılıp kapanan bölüm. */
    private fun langLabel(choice: String): String =
        if (choice == I18n.AUTO) t("Otomatik (telefonun dili): {0}", I18n.LANGS.first { it.code == I18n.resolve(I18n.AUTO) }.name)
        else I18n.LANGS.firstOrNull { it.code == choice }?.name ?: choice

    private fun pickLanguage() {
        val codes = listOf(I18n.AUTO) + I18n.LANGS.map { it.code }
        val names = codes.map { langLabel(it) }.toTypedArray()
        val current = codes.indexOf(I18n.choice(this)).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("🌐 " + t("Dil"))
            .setSingleChoiceItems(names, current) { d, which ->
                d.dismiss()
                if (which == current) return@setSingleChoiceItems
                AlertDialog.Builder(this).setMessage(t("Dil değişince NewREX yeniden başlar. Araçtaysan uygulamayı araç ekranından tekrar aç."))
                    .setPositiveButton(t("Tamam")) { _, _ -> I18n.applyAndRestart(this, codes[which]) }
                    .setNegativeButton(t("Vazgeç"), null).show()
            }.show()
    }

    private fun section(icon: Int, color: Int, title: String, summary: TextView, vararg body: View): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(6), 0, 0)
            body.forEach { addView(it, lp(MATCH, WRAP).margins(t = dp(8))) }
        }
        val chevron = icon(R.drawable.ic_chevron_right, 22, Palette.TEXT_FAINT)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@MainActivity).apply {
                background = rounded(Palette.withAlpha(color, 0x40), dpf(12))
                addView(icon(icon, 22, color), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }, lp(dp(42), dp(42)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 16f, bold = true))
                addView(summary, lp(MATCH, WRAP).margins(t = dp(3)))
            }, lp(0, WRAP, 1f).margins(l = dp(14)))
            addView(chevron, lp(dp(22), dp(22)))
            setOnClickListener {
                val open = content.visibility != View.VISIBLE
                content.visibility = if (open) View.VISIBLE else View.GONE
                chevron.animate().rotation(if (open) 90f else 0f).setDuration(180).start()
            }
        }
        return panel().apply {
            setPadding(dp(14), dp(12), dp(14), dp(14))
            // Koyu cam kart: arkadaki dalga çizgileri yazıların içinden geçmesin
            background = rounded(0xE6121419.toInt(), dpf(18), dp(1), 0x1FFFFFFF)
            layoutTransition = LayoutTransition()
            addView(head, lp(MATCH, WRAP))
            addView(content, lp(MATCH, WRAP))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(10))
        }
    }

    private fun summary() = label("", 13f, Palette.TEXT_DIM, lines = 1)

    // ---- Yakıt ve kilometre ----

    private fun refreshCar() {
        carBox.removeAllViews()
        val odo = CarLog.estimatedOdometer()
        val stats = CarLog.fuelStats(odo)
        carSummary.text = listOfNotNull(
            odo?.let { String.format(tr, "~%,.0f km", it) },
            stats.consumption?.let { "ort %.1f L/100 km".format(it) },
        ).joinToString(" • ").ifEmpty { t("Kilometre, yakıt alımları, yağ değişimi") }

        carBox.addView(label(t("Araç kilometre ve yakıt bilgisini paylaşmıyorsa bu değerlerle tahmin yapılır: ") +
            t("girdiğin kilometreye GPS ile gidilen yol eklenir."), 13f, Palette.TEXT_DIM, lines = 4))

        carBox.addView(numberRow(t("Kilometre sayacı"), odo?.let { "%.0f".format(it) } ?: "", "km") { CarLog.setOdometer(it) })
        carBox.addView(numberRow("Depo hacmi", "%.0f".format(CarLog.tankLiters), "L") { CarLog.tankLiters = it })
        carBox.addView(numberRow(t("Son yağ değişimi"), if (CarLog.oilChangeKm > 0) "%.0f".format(CarLog.oilChangeKm) else "", "km") {
            CarLog.oilChangeKm = it
        })
        carBox.addView(numberRow(t("Yağ değişim aralığı"), "%.0f".format(CarLog.oilIntervalKm), "km") { CarLog.oilIntervalKm = it })
        CarLog.oilKmLeft(odo)?.let { left ->
            carBox.addView(label(if (left < 0) String.format(tr, t("🛢️ Yağ değişimi %,.0f km gecikti!"), -left)
                else String.format(tr, t("🛢️ Yağ değişimine %,.0f km kaldı"), left), 14f,
                if (left < 1000) Palette.ORANGE else Palette.GREEN, bold = true), lp(MATCH, WRAP).margins(t = dp(6)))
        }

        carBox.addView(label(t("⛽ Yakıt defteri"), 15f, bold = true), lp(MATCH, WRAP).margins(t = dp(14)))
        val fuel = CarLog.fuel()
        if (fuel.isNotEmpty()) {
            carBox.addView(label(listOf(
                t("Ortalama: ") + (stats.consumption?.let { "%.1f L/100 km".format(it) } ?: t("en az 2 dolum gerekli")),
                t("Km başı: ") + (stats.costPerKm?.let { "%.2f ₺".format(it) } ?: "—"),
                t("Bu ay: ") + String.format(tr, "%,.0f ₺", stats.thisMonth),
                t("Tahmini menzil: ") + (stats.rangeKm?.let { "~%.0f km".format(it) } ?: "—"),
            ).joinToString("\n"), 14f, Palette.TEXT_DIM, lines = 4), lp(MATCH, WRAP).margins(t = dp(4)))
        }
        val fmt = java.text.SimpleDateFormat("d MMM yyyy", tr)
        fuel.reversed().take(8).forEach { e ->
            carBox.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(6), dp(8))
                background = rounded(0x14FFFFFF, dpf(12))
                addView(label(fmt.format(java.util.Date(e.time)) + "\n" +
                    String.format(tr, "%.1f L • %,.0f ₺ • %,.0f km", e.liters, e.price, e.km), 13f, lines = 2), lp(0, WRAP, 1f))
                addView(Button(this@MainActivity).apply {
                    text = t("Sil")
                    setOnClickListener { CarLog.removeFuel(e) }
                })
            }, lp(MATCH, WRAP).margins(t = dp(6)))
        }
        carBox.addView(button(t("Yakıt aldım (depoyu fulledim)")) { addFuelDialog() }, lp(MATCH, WRAP).margins(t = dp(10)))
    }

    /** Başlık + sayı kutusu + "Kaydet". */
    private fun numberRow(title: String, value: String, unit: String, onSave: (Float) -> Unit): View {
        val field = EditText(this).apply {
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            gravity = Gravity.END
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 14f, lines = 2), lp(0, WRAP, 1f))
            addView(field, lp(dp(90), WRAP))
            addView(label(unit, 13f, Palette.TEXT_DIM), lp(dp(28), WRAP).margins(l = dp(4)))
            addView(Button(this@MainActivity).apply {
                text = t("Kaydet")
                isAllCaps = false
                setOnClickListener {
                    field.text.toString().replace(',', '.').toFloatOrNull()?.takeIf { it > 0 }?.let(onSave)
                    field.clearFocus()
                }
            })
        }
    }

    private fun addFuelDialog() {
        fun field(hint: String, value: String = "") = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val liters = field(t("Litre (ör. 38,5)"))
        val price = field(t("Tutar ₺ (ör. 1850)"))
        val km = field(t("Kilometre sayacı"), CarLog.estimatedOdometer()?.let { "%.0f".format(it) } ?: "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(liters); addView(price); addView(km)
            addView(label(t("Depoyu fullediğin alımları gir; tüketim böyle doğru hesaplanır. Kilometreyi aracın göstergesinden yaz."),
                12f, Palette.TEXT_DIM, lines = 3), lp(MATCH, WRAP).margins(t = dp(6)))
        }
        AlertDialog.Builder(this)
            .setTitle(t("Yakıt aldım"))
            .setView(box)
            .setPositiveButton(t("Kaydet")) { _, _ ->
                fun num(e: EditText) = e.text.toString().replace(',', '.').toFloatOrNull() ?: 0f
                if (num(liters) > 0f) CarLog.addFuel(FuelEntry(System.currentTimeMillis(), num(liters), num(price), num(km)))
            }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    // ---- Tema ----

    private fun paintLogo() {
        logo.text = SpannableStringBuilder("NewREX").apply {
            setSpan(ForegroundColorSpan(Palette.accent), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        logo.neonText(12)
    }

    private fun paintWave(animate: Boolean = true) {
        wave.setPalette(0, Palette.accent, Palette.companion(Palette.accent), animate, base = Palette.safeBackground(Prefs.bgColor))
        themeSummaryText()
    }

    private fun themeSummaryText() {
        if (::themeSummary.isInitialized)
            themeSummary.text = if (Prefs.bgColor == 0) t("Tema rengi ve arka plan") else t("Tema rengi ve özel arka plan")
    }

    /** Tema değişti: araç ekranı kendini zaten yeniler; burada logo ve arka planı güncelliyoruz. */
    private fun themeChanged() {
        Palette.load()
        paintLogo()
        paintWave()
    }

    private fun themeBody(): Array<View> {
        val accentPicker = colorPicker(Palette.accent, 1f) { Prefs.customAccent = it; themeChanged() }
        val bgStart = Palette.safeBackground(Prefs.bgColor).takeIf { it != 0 } ?: Palette.BG_TOP
        val bgPicker = colorPicker(bgStart, Palette.MAX_BG_VALUE) { Prefs.bgColor = it; themeChanged() }
        val accentRow = swatchRow((Palette.accents + Palette.extraAccents).toList(), accentPicker, { Palette.accent }) { c ->
            val i = Palette.accents.indexOf(c)
            if (i >= 0) Prefs.accentIndex = i else Prefs.customAccent = c
            themeChanged()
        }
        val bgRow = swatchRow(Palette.backgrounds.toList(), bgPicker, { Prefs.bgColor }) { c -> Prefs.bgColor = c; themeChanged() }
        return arrayOf(
            label(t("Tema rengi"), 15f, bold = true), accentRow, accentPicker,
            label(t("Arka plan"), 15f, bold = true), bgRow, bgPicker,
            label(t("Sondaki + düğmesiyle istediğin rengi seçebilirsin. Arka plan yazılar okunsun diye fazla açık olamaz. Değişiklik araç ekranına hemen yansır."),
                12f, Palette.TEXT_FAINT, lines = 3),
        )
    }

    /** Hazır renkler + sonda gökkuşağı düğmesi (altındaki renk seçiciyi açar/kapatır). */
    private fun swatchRow(colors: List<Int>, picker: View, current: () -> Int, onPick: (Int) -> Unit): View {
        // Sığmayan renkler alt satıra geçer (yana kaydırınca sondaki + görünmüyordu); seçili renk beyaz halkalı
        val row = com.newrex.auto.ui.WrapLayout(this, dp(10), dp(10))
        val dots = ArrayList<Pair<TextView, Int>>()
        fun mark() = dots.forEach { (v, c) ->
            val sel = c == current()
            v.background = rounded(if (c == 0) Palette.BG_TOP else c, dpf(20), if (sel) dp(3) else dp(1), if (sel) Color.WHITE else 0x55FFFFFF)
        }
        colors.forEach { c ->
            val v = TextView(this).apply {
                if (c == 0) { text = t("Oto"); setTextColor(Color.WHITE); textSize = 11f }
                gravity = Gravity.CENTER
                includeFontPadding = false
                setOnClickListener { picker.visibility = View.GONE; onPick(c); mark() }
            }
            dots += v to c
            row.addView(v, android.view.ViewGroup.LayoutParams(dp(40), dp(40)))
        }
        mark()
        row.addView(TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(dpf(4), 0f, 0f, 0xAA000000.toInt())
            background = ColorPickerView.rainbowDot()
            setOnClickListener { picker.visibility = if (picker.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        }, android.view.ViewGroup.LayoutParams(dp(40), dp(40)))
        return row
    }

    /** Kare + renk çubuğu + HEX kutusu. Başta kapalıdır, gökkuşağı düğmesiyle açılır. */
    private fun colorPicker(initial: Int, maxValue: Float, onPick: (Int) -> Unit): View {
        val picker = ColorPickerView(this, initial, maxValue)
        val hexBox = EditText(this).apply {
            setSingleLine()
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setText(ColorPickerView.hex(picker.color))
            background = rounded(0x22FFFFFF, dpf(10), dp(1), 0x33FFFFFF)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ ->
                ColorPickerView.parseHex(v.text.toString())?.let { c ->
                    picker.setColor(c)
                    setText(ColorPickerView.hex(picker.color))
                    onPick(picker.color)
                }
                false
            }
        }
        val preview = View(this)
        fun show(c: Int) { preview.background = rounded(c, dpf(10)) }
        show(picker.color)
        picker.onChange = { show(it); hexBox.setText(ColorPickerView.hex(it)) }
        picker.onCommit = { onPick(it) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(picker, lp(MATCH, dp(220)))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(preview, lp(dp(44), dp(40)))
                addView(label("HEX", 13f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(l = dp(12), r = dp(8)))
                addView(hexBox, lp(0, dp(40), 1f))
            }, lp(MATCH, WRAP).margins(t = dp(6)))
        }
    }

    // ---- Saat ve radyo görünümü ----

    private fun looksBody(): Array<View> {
        val clocks = ClockView.STYLES.indices.map { i -> ClockView(this, i) }
        val fms = FmDisplayView.STYLES.indices.map { i -> FmDisplayView(this, i).apply { freq = 1000 } }
        val clockRow = choiceRow(clocks, ClockView.STYLES.map { it.name + if (it.analog) " ◷" else "" }, dp(150), dp(120),
            { Prefs.clockStyle }) { Prefs.clockStyle = it }
        val fmRow = choiceRow(fms, FmDisplayView.STYLES.map { it.name }, dp(210), dp(90), { Prefs.fmStyle }) { Prefs.fmStyle = it }
        refreshLooksSummary()
        return arrayOf(
            label(t("Saat"), 15f, bold = true), clockRow,
            label(t("Radyo frekans göstergesi"), 15f, bold = true), fmRow,
            label(t("◷ olanlar yuvarlak (akrepli) saat. Seçtiğin tasarım araç ekranına hemen yansır."), 12f, Palette.TEXT_FAINT, lines = 2),
        )
    }

    private fun refreshLooksSummary() {
        looksSummary.text = t("Saat: {0} · ", ClockView.STYLES[Prefs.clockStyle.coerceIn(0, ClockView.STYLES.lastIndex)].name) +
            t("Radyo: {0}", FmDisplayView.STYLES[Prefs.fmStyle.coerceIn(0, FmDisplayView.STYLES.lastIndex)].name)
    }

    /** Yatay kaydırılan önizleme kartları; seçili olanın çerçevesi tema renginde. */
    private fun choiceRow(previews: List<View>, names: List<String>, w: Int, h: Int, current: () -> Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cards = previews.mapIndexed { i, v ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(6))
                addView(v, lp(MATCH, h))
                addView(label(names[i], 13f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(4)))
                row.addView(this, lp(w, WRAP).margins(r = dp(10)))
            }
        }
        fun paint() = cards.forEachIndexed { i, c ->
            val sel = i == current()
            c.background = rounded(if (sel) Palette.withAlpha(Palette.accent, 0x26) else 0x22000000, dpf(16),
                dp(if (sel) 2 else 1), if (sel) Palette.accent else 0x22FFFFFF)
        }
        cards.forEachIndexed { i, c -> c.setOnClickListener { onPick(i); paint(); refreshLooksSummary() } }
        paint()
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun refreshPhotos() {
        val photos = Board.photos.value
        photosSummary.text = if (photos.isEmpty()) t("Henüz fotoğraf yok") else t("{0} fotoğraf", photos.size)
        photosBox.removeAllViews()
        val gap = dp(8)
        val cellSize = (resources.displayMetrics.widthPixels - dp(18 * 2 + 14 * 2) - gap * 2) / 3
        photos.chunked(3).forEach { rowPhotos ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowPhotos.forEachIndexed { i, p ->
                val cell = FrameLayout(this)
                cell.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(0x22FFFFFF, dpf(12))
                    clipToOutline = true
                    load(Board.file(p.file)) { size(cellSize, cellSize) }
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                cell.addView(TextView(this).apply {
                    text = "✕"
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = rounded(0xCC000000.toInt(), dpf(14))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setMessage(t("Bu fotoğraf silinsin mi?"))
                            .setPositiveButton(t("Sil")) { _, _ -> Board.deletePhoto(p.file) }
                            .setNegativeButton(t("Vazgeç"), null).show()
                    }
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).margins(t = dp(6), r = dp(6)))
                row.addView(cell, lp(cellSize, cellSize).margins(r = if (i < 2) gap else 0))
            }
            photosBox.addView(row, lp(MATCH, WRAP).margins(t = gap))
        }
    }

    /** Uygulama arabada görünmüyorsa ekran görüntüsü alınıp geliştiriciye gönderilecek bilgiler. */
    /** Görünmeme sorununun olası sebebi, sade bir dille. */
    private fun verdict(): String {
        val info = if (Build.VERSION.SDK_INT >= 30) runCatching { packageManager.getInstallSourceInfo(packageName) }.getOrNull() else null
        @Suppress("DEPRECATION")
        val installer = info?.installingPackageName ?: runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull()
        val initiating = info?.initiatingPackageName
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val sleeping = !pm.isIgnoringBatteryOptimizations(packageName)
        val brand = Build.MANUFACTURER.lowercase()
        val oem = listOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor", "vivo").any { brand.contains(it) }
        val head = when {
            installer != "com.android.vending" ->
                t("❌ Android Auto bu kurulumu tanımıyor. Aşağıdaki \"Arabada görünür yap\" düğmesine bas.")
            initiating != null && initiating !in SelfInstall.INSTALLERS ->
                t("❌ Telefonun kendi yükleyicisi kullanılmış ({0}). Aşağıdaki \"Arabada görünür yap\" düğmesine bas.", initiating)
            CarEntry.effective(this) == CarEntry.PARKED && Prefs.lastParkedSession == 0L ->
                t("⚠️ Park kapısı açık ama araçta henüz açılmadı. Android Auto → Geliştirici ayarları → \"Bilinmeyen kaynaklar\" ") +
                    t("açık olmalı; sonra telefonu arabaya bağla, park halindeyken uygulamalar listesinde NewREX'e dokun.")
            CarEntry.effective(this) == CarEntry.PARKED -> t("✅ Park kapısı çalışıyor.")
            Prefs.lastCarBind == 0L ->
                t("⚠️ Kurulum doğru ama Android Auto NewREX'i hiç açmayı denemedi. Android Auto → Geliştirici ayarları → ") +
                    t("\"Bilinmeyen kaynaklar\" açık olmalı; açıksa kapatıp tekrar aç ve telefonu yeniden başlat.") +
                    if (oem) t(" Bu marka ({0}) Android Auto'da ek kısıtlama yapabiliyor.", brand) else ""
            Prefs.lastCarSession == 0L ->
                t("⚠️ Android Auto NewREX'i gördü ama ekranda açmadı. Android Auto → \"Başlatıcıyı özelleştir\" listesinde NewREX'i işaretle.")
            else -> t("✅ Kurulum ve bağlantı düzgün görünüyor.")
        }
        return if (sleeping) t("{0}\n🔋 Pil tasarrufu NewREX'i uyutabiliyor; aşağıdaki düğmeyle kısıtlamayı kaldır.", head) else head
    }

    /** Basit seçim düğmeleri (ör. TV kalitesi). */
    private fun choiceButtons(names: List<String>, get: () -> Int, set: (Int) -> Unit, summary: TextView, note: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paint() {
            for (i in 0 until row.childCount) {
                val sel = i == get()
                row.getChildAt(i).background = rounded(if (sel) Palette.withAlpha(Palette.accent, 0x33) else 0x22FFFFFF, dpf(18),
                    dp(if (sel) 2 else 1), if (sel) Palette.accent else 0x33FFFFFF)
            }
            summary.text = names.getOrNull(get()) ?: ""
        }
        names.forEachIndexed { i, n ->
            row.addView(TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnClickListener { set(i); paint() }
            }, lp(0, dp(40), 1f).margins(r = dp(6)))
        }
        paint()
        box.addView(row, lp(MATCH, WRAP))
        box.addView(label(note, 13f, Palette.TEXT_DIM, lines = 3), lp(MATCH, WRAP).margins(t = dp(8)))
        return box
    }

    /** Tasarruf modu: Kapalı / Açık / Otomatik. */
    private fun saverBody(summary: TextView): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val info = label("", 13f, Palette.TEXT_DIM, lines = 5)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun paint() {
            for (i in 0 until row.childCount) {
                val sel = i == Prefs.saverMode
                row.getChildAt(i).background = rounded(if (sel) Palette.withAlpha(Palette.accent, 0x33) else 0x22FFFFFF, dpf(18),
                    dp(if (sel) 2 else 1), if (sel) Palette.accent else 0x33FFFFFF)
            }
            val why = Saver.reason()
            summary.text = if (why != null) t("Açık ({0})", why) else Saver.NAMES[Prefs.saverMode]
            info.text = t("Açıkken arka plan sabit durur, dokununca çıkan efektler kapanır. Görüntü ve ses kalitesi değişmez. ") +
                t("Otomatik: telefon pil tasarrufundayken ya da ısındığında kendiliğinden açılır.")
        }
        Saver.NAMES.forEachIndexed { i, n ->
            row.addView(TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnClickListener { Prefs.saverMode = i; paint() }
            }, lp(0, dp(40), 1f).margins(r = dp(6)))
        }
        paint()
        box.addView(row, lp(MATCH, WRAP))
        box.addView(info, lp(MATCH, WRAP).margins(t = dp(8)))
        return box
    }

    /**
     * Normal APK kurulumunda Android Auto NewREX'i sadece park halindeyken açar. Uygulama her açılışta, bu
     * düzelene kadar, sürüşte de çalışması için gereken adımları nazikçe anlatır ve tek dokunuşla başlatır.
     */
    private fun showDrivingGuide() {
        AlertDialog.Builder(this)
            .setTitle(t("Sürüşte de çalışması için son bir adım"))
            .setMessage(t("NewREX kuruldu 🎉 Şu an arabada sadece park halindeyken açılıyor. Sürüşte de kullanabilmek için ") +
                t("bir kez şunları yapman yeterli:\n\n") +
                t("1. Play Store → sağ üstte profil resmin → Play Protect → ⚙️ → \"Uygulamaları Play Protect ile tara\"yı kısa süreliğine kapat.\n") +
                t("    Samsung'daysan: Ayarlar → Güvenlik ve gizlilik → Otomatik Engelleyici'yi de kapat.\n\n") +
                t("2. Buraya dön ve \"Şimdi yap\"a bas. Açılan ekranda \"Güncelle\"ye bas.\n\n") +
                t("3. Kapattığın ayarları geri aç. Bir şey sorarsa \"Gönderme\" de.\n\n") +
                t("4. Telefonu arabadan çıkarıp tekrar bağla. Hepsi bu!"))
            .setPositiveButton(t("Şimdi yap")) { _, _ -> fixInstall() }
            .setNegativeButton(t("Sonra"), null)
            .show()
    }

    /**
     * "Güncelle"ye basıldı ama Android Auto kurulumu hâlâ tanımıyor (çoğunlukla Xiaomi/Redmi/POCO'da telefonun kendi
     * yükleyicisi araya giriyor). Her açılışta aynı adımı istemek yerine bir kez ne olduğunu anlatır; ekran
     * görüntüsü geliştiriciye gönderilebilsin diye teknik bilgiyi de yazar.
     */
    private fun showInstallFailed() {
        Prefs.installFailShown = BuildConfig.VERSION_CODE
        val info = if (Build.VERSION.SDK_INT >= 30) runCatching { packageManager.getInstallSourceInfo(packageName) }.getOrNull() else null
        @Suppress("DEPRECATION")
        val installer = info?.installingPackageName ?: runCatching { packageManager.getInstallerPackageName(packageName) }.getOrNull()
        AlertDialog.Builder(this)
            .setTitle(t("Kurulum bu telefonda tutmadı"))
            .setMessage(t("\"Güncelle\"ye bastın ama Android Auto NewREX'i hâlâ tanımıyor. Bu, bazı telefonlarda (özellikle ") +
                t("Xiaomi, Redmi ve POCO) telefonun kendi yükleyicisi araya girdiğinde oluyor. Merak etme, NewREX arabada ") +
                t("park halindeyken çalışmaya devam eder.\n\n") +
                t("Bir kez daha denemek istersen:\n") +
                t("• Play Protect'in kapalı olduğundan emin ol.\n") +
                t("• Açılan ekran telefonun kendi ekranıysa (güvenlik taraması vb.) \"Yine de yükle\" ya da \"Devam\" de.\n\n") +
                t("Yine olmazsa bu pencerenin ekran görüntüsünü geliştiriciye gönder:\n") +
                "${Build.MANUFACTURER} ${Build.MODEL} • Android ${Build.VERSION.RELEASE}\n" +
                "Kuran: ${installer ?: "-"} / ${info?.initiatingPackageName ?: "-"}\n" +
                SelfInstall.report(this))
            .setPositiveButton(t("Tekrar dene")) { _, _ -> fixInstall() }
            .setNegativeButton(t("Tamam"), null)
            .show()
    }

    /** "Arabada görünür yap": King Installer'ın işini uygulamanın içinden yapar. */
    private fun fixRow(): View {
        if (BuildConfig.PARK_ONLY) return View(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(button(t("Arabada görünür yap")) { fixInstall() }, lp(MATCH, WRAP).margins(t = dp(8)))
        box.addView(label(t("Yükleyiciler:\n") + SelfInstall.report(this), 12f, Palette.TEXT_FAINT, lines = 4), lp(MATCH, WRAP).margins(t = dp(4)))
        return box
    }

    private fun fixInstall() {
        fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_LONG).show()
        if (!SelfInstall.canRequestInstalls(this)) {
            toast(t("Önce NewREX'e \"bilinmeyen uygulamaları yükleme\" izni ver, sonra tekrar bas."))
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            return
        }
        val installer = SelfInstall.available(this)
        if (installer == null) {
            val disabled = SelfInstall.INSTALLERS.firstOrNull { SelfInstall.state(this, it) == SelfInstall.State.DISABLED }
            AlertDialog.Builder(this)
                .setTitle(t("Google yükleyicisi kapalı"))
                .setMessage(if (disabled != null)
                    t("Bu telefonda Google'ın paket yükleyicisi kapatılmış. Bilgisayara bağlayıp şu komutu bir kez çalıştır, sonra tekrar bas:\n\nadb shell pm enable {0}", disabled)
                else t("Bu telefonda Google'ın paket yükleyicisi bulunamadı. Bu Yardım bölümünün ekran görüntüsünü geliştiriciye gönder."))
                .setPositiveButton(t("Tamam"), null)
                .show()
            return
        }
        toast(t("Açılan ekranda \"Güncelle\" ya da \"Yükle\"ye bas. NewREX yeniden açılınca bu bölüme tekrar bak."))
        if (!SelfInstall.start(this, installer)) toast(t("Yükleyici açılamadı. Bu bölümün ekran görüntüsünü geliştiriciye gönder."))
        else {
            // Tutmazsa bir sonraki açılışta "tekrar Güncelle'ye bas" yerine ne olduğunu anlatan bilgi çıksın
            Prefs.selfInstallTried = BuildConfig.VERSION_CODE
            Prefs.installFailShown = 0
        }
    }

    /** Arabaya giriş kapısı seçimi: Otomatik / Normal / Park. */
    private fun entryRow(): View {
        if (BuildConfig.PARK_ONLY) return label(t("Bu NewREX'in Park sürümü: arabada park halindeyken açılır, King Installer gerekmez. ") +
            t("Android Auto → Geliştirici ayarları → \"Bilinmeyen kaynaklar\" açık olmalı."), 13f, Palette.TEXT_DIM, lines = 4)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val info = label("", 13f, Palette.TEXT_DIM, lines = 4)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val names = listOf(t("Otomatik"), t("Normal"), t("Park"))
        fun paint() {
            for (i in 0 until row.childCount) {
                val sel = i == Prefs.carEntry
                row.getChildAt(i).background = rounded(if (sel) Palette.withAlpha(Palette.accent, 0x33) else 0x22FFFFFF, dpf(18),
                    dp(if (sel) 2 else 1), if (sel) Palette.accent else 0x33FFFFFF)
            }
            info.text = (if (CarEntry.effective(this) == CarEntry.PARKED)
                t("Şu an: Park kapısı. Arabada her kurulumda görünür ama sadece park halindeyken açılır.")
            else t("Şu an: Normal kapı. Sürüşte de açılır (video kapanır); arabada görünmesi için King Installer ile kurulmuş olmalı.")) +
                if (!CarEntry.parkedSupported()) t("\nPark kapısı Android 15 ve üstünde var.") else t("\nDeğiştirdikten sonra telefonu arabadan çıkarıp tekrar bağla.")
        }
        names.forEachIndexed { i, n ->
            row.addView(TextView(this).apply {
                text = n
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 14f
                setOnClickListener { Prefs.carEntry = i; CarEntry.apply(this@MainActivity); paint() }
            }, lp(0, dp(40), 1f).margins(r = dp(6)))
        }
        paint()
        box.addView(label(t("Arabaya giriş kapısı"), 14f, bold = true))
        box.addView(row, lp(MATCH, WRAP).margins(t = dp(6)))
        box.addView(info, lp(MATCH, WRAP).margins(t = dp(6)))
        return box
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { android.widget.Toast.makeText(this, t("Zaten kısıtlama yok."), android.widget.Toast.LENGTH_SHORT).show(); return }
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun diagnostics(): String {
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", tr)
        fun time(t: Long) = if (t == 0L) t("hiç olmadı") else fmt.format(java.util.Date(t))
        val install = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                val i = packageManager.getInstallSourceInfo(packageName)
                val source = if (Build.VERSION.SDK_INT >= 33) " • kaynak=${i.packageSource}" else ""
                t("yükleyen={0} • başlatan={1} • kaynak uygulama={2}{3}", i.installingPackageName, i.initiatingPackageName, i.originatingPackageName, source)
            } else {
                @Suppress("DEPRECATION")
                t("yükleyen={0}", packageManager.getInstallerPackageName(packageName))
            }
        }.getOrElse { t("okunamadı") }
        val aa = runCatching {
            packageManager.getPackageInfo("com.google.android.projection.gearhead", 0).versionName
        }.getOrElse { t("bulunamadı") }
        return t("Telefon: {0} {1} • Android {2} (API {3})\n", Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT) +
            t("NewREX {0} • {1}\n", BuildConfig.VERSION_NAME, install) +
            t("Android Auto: {0}\n", aa) +
            t("Araç bağlanmayı denedi: {0}\n", time(Prefs.lastCarBind)) +
            t("Araç ekranı açıldı: {0}\n\n", time(Prefs.lastCarSession)) +
            t("Uygulama arabada görünmüyorsa bu bölümün ekran görüntüsünü geliştiriciye gönder.")
    }

    private fun refreshSelf() {
        val running = DirectApps.ownRunning()
        selfSummary.text = when {
            running -> t("Hazır")
            DirectApps.granted() -> t("Shizuku ile hazır")
            SelfAdb.paired(this) -> t("Eşleşti · Wi-Fi'de kendiliğinden başlar")
            else -> t("Kurulum gerekli")
        }
        selfStatus.text = listOfNotNull(
            if (running) t("Yardımcı çalışıyor. Telefon kapanana kadar açık kalır.") else null,
            if (!SelfAdb.paired(this)) t("En kolayı en üstteki Kurulum ekranından yapmak. Ya da: “Eşleştir”e bas, açılan sayfada “Eşleme kodu ile cihaz eşle”ye bas, bildirimde “Kodu yaz”a basıp 6 haneli kodu yaz.") else null,
            if (SelfAdb.paired(this) && !running) t("Telefon Wi-Fi'ye bağlıyken “Şimdi başlat”a bas ya da biraz bekle; kendiliğinden başlar.") else null,
        ).joinToString("\n\n")
    }

    private fun refreshMirror() {
        val on = Mirror.active.value
        mirrorStatus.text = if (on) t("✅ Ekran araca yansıtılıyor. Araçta NewREX → Yansıt'a gir.")
        else t("Telefonundaki her şeyi (oyun, sosyal medya, uygulamalar) araç ekranında göster. Her başlatmada telefonda bir kez \"Başlat\" onayı istenir. ") +
            t("Netflix gibi korumalı uygulamalar siyah görünür. Yansıtma sırasında telefon ekranı açık kalır; uygulamaları telefonun kendi ekranından kullanırsın.")
        mirrorButton.text = if (on) t("Yansıtmayı durdur") else t("Ekranı arabaya yansıt")
        mirrorSummary.text = if (on) t("✅ Yansıtılıyor") else t("Telefon ekranını araçta göster")
    }

    private fun refreshPark() {
        parkButtons.removeAllViews()
        val spot = Prefs.parkedSpot
        if (spot == null) {
            parkStatus.text = t("Araç kapanınca (Android Auto bağlantısı kesilince) bulunduğun yer otomatik kaydedilir. ") +
                t("Henüz kayıtlı park yeri yok.")
            parkSummary.text = t("Henüz kayıtlı park yeri yok")
        } else {
            val time = java.text.SimpleDateFormat("d MMMM yyyy, HH:mm", tr).format(java.util.Date(spot.time))
            parkStatus.text = t("🅿️ Son park: {0}\nKonum hassasiyeti: ~{1} m", time, spot.accuracy.toInt())
            parkSummary.text = t("Son park: {0}", time)
            parkButtons.addView(button(t("Haritada göster")) { openMap(spot, walk = false) }, lp(MATCH, WRAP).margins(t = dp(8)))
            parkButtons.addView(button(t("Yürüyerek yol tarifi")) { openMap(spot, walk = true) }, lp(MATCH, WRAP).margins(t = dp(8)))
            parkButtons.addView(button(t("Park yerini sil")) { Prefs.parkedSpot = null; refreshPark() }, lp(MATCH, WRAP).margins(t = dp(8)))
            val ago = (System.currentTimeMillis() - spot.time) / 60_000
            parkStatus.append("\n⏱️ " + (if (ago >= 60) "${ago / 60} sa ${ago % 60} dk" else t("{0} dk", ago)) + t("dır park halinde"))
        }
        // Otopark süresi hatırlatıcısı
        val timerEnd = ParkTimer.endTime(this)
        parkButtons.addView(label(if (timerEnd > 0) t("⏰ Otopark süren ") +
            java.text.SimpleDateFormat("HH:mm", tr).format(java.util.Date(timerEnd)) + t(" itibarıyla doluyor (10 dk önce bildirim gelir)")
            else t("⏰ Otopark süresi hatırlatıcısı kur:"), 14f, Palette.TEXT_DIM, lines = 3), lp(MATCH, WRAP).margins(t = dp(14)))
        val timers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(30 to "30 dk", 60 to "1 sa", 120 to "2 sa", 180 to "3 sa").forEach { (m, t) ->
            timers.addView(button(t) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
                ParkTimer.start(this, m)
                refreshPark()
            }, lp(0, WRAP, 1f).margins(r = dp(6)))
        }
        if (timerEnd > 0) timers.addView(button(t("İptal")) { ParkTimer.cancel(this); refreshPark() }, lp(0, WRAP, 1f))
        parkButtons.addView(timers, lp(MATCH, WRAP).margins(t = dp(8)))
        if (timerEnd > 0) parkSummary.text = parkSummary.text.toString() + t(" • hatırlatıcı açık")
        parkButtons.addView(button(t("Şu anki yerimi park yeri olarak kaydet")) {
            val loc = DriveState.bestLocation(this)
            if (loc == null) {
                AlertDialog.Builder(this).setMessage(t("Konum alınamadı. Konum iznini ver ve konumu aç.")).setPositiveButton(t("Tamam"), null).show()
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
        maintenanceSummary.text = Prefs.maintenanceDue().firstOrNull()?.let { (m, d) ->
            if (d < 0) t("{0} {1} {2} gün gecikti", m.emoji, m.title, -d) else t("{0} {1}: {2} gün kaldı", m.emoji, m.title, d)
        } ?: t("Muayene, sigorta, yağ değişimi…")
        val today = java.time.LocalDate.now()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", tr)
        Maintenance.values().forEach { m ->
            val day = Prefs.maintenanceDay(m)?.let { java.time.LocalDate.ofEpochDay(it) }
            val left = day?.let { it.toEpochDay() - today.toEpochDay() }
            val (status, color) = when {
                left == null -> t("Tarih girilmedi") to Palette.TEXT_FAINT
                left < 0 -> t("{0} • {1} gün gecikti", fmt.format(day), -left) to Palette.RED
                left <= 15 -> t("{0} • {1} gün kaldı", fmt.format(day), left) to Palette.ORANGE
                else -> t("{0} • {1} gün kaldı", fmt.format(day), left) to Palette.GREEN
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
                    text = t("Sil")
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

    private lateinit var obdSummary: TextView

    private fun obdText() = if (Prefs.obdMac.isBlank()) t("Seçilmedi") else Prefs.obdName.ifBlank { Prefs.obdMac }

    /** Eşleşmiş Bluetooth cihazlarından OBD adaptörünü seçtirir (önce Bluetooth izni). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun pickObd() {
        if (!com.newrex.auto.data.Obd.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BT)
            return
        }
        val devices = com.newrex.auto.data.Obd.pairedDevices(this)
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setMessage(t("Eşleşmiş Bluetooth cihazı yok. Adaptörü arabaya tak, kontağı aç, telefonun Bluetooth ayarlarından eşleştir (şifre genelde 1234 ya da 0000) ve tekrar dene."))
                .setPositiveButton(t("Bluetooth ayarları")) { _, _ -> runCatching { startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                .setNegativeButton(t("Tamam"), null).show()
            return
        }
        val names = devices.map { d -> runCatching { d.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: d.address }
        AlertDialog.Builder(this).setTitle(t("OBD-II adaptörünü seç"))
            .setItems(names.toTypedArray()) { _, i ->
                Prefs.obdMac = devices[i].address
                Prefs.obdName = names[i]
                com.newrex.auto.data.Obd.restart(this)
                obdSummary.text = obdText()
            }
            .setNegativeButton(t("Vazgeç"), null).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_BT) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickObd()
            return
        }
        refreshLocation()
        // Araç bağlıysa hız ve yolculuk ölçümü hemen başlasın
        DriveState.ensureGps()
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
        } ?: t("Liste dosyası")
        Prefs.playlists = Prefs.playlists + Playlist(name.substringBeforeLast('.'), uri.toString())
        refreshPlaylists()
    }

    private fun refreshLocation() {
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        locationCard.visibility = if (granted) View.GONE else View.VISIBLE
        mediaCard.visibility = if (missingMediaPermissions().isEmpty()) View.GONE else View.VISIBLE
    }

    private fun missingMediaPermissions(): Array<String> {
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.RECORD_AUDIO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO)
        return wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    private fun refreshPlaylists() {
        playlistBox.removeAllViews()
        val lists = Prefs.playlists
        playlistSummary.text = if (lists.isEmpty()) t("Liste yok") else lists.joinToString(", ") { it.name }
        if (lists.isEmpty()) {
            playlistBox.addView(label(t("Henüz liste yok."), 14f, Palette.TEXT_FAINT), lp(MATCH, WRAP).margins(t = dp(10)))
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
                    text = t("Sil")
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
        val name = EditText(this).apply { hint = t("Liste adı (ör. Evdeki liste)"); setSingleLine() }
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
            .setTitle(t("IPTV listesi ekle"))
            .setView(box)
            .setPositiveButton(t("Ekle")) { _, _ ->
                val u = url.text.toString().trim()
                if (u.startsWith("http://") || u.startsWith("https://")) {
                    val n = name.text.toString().trim().ifEmpty { t("Liste {0}", Prefs.playlists.size + 1) }
                    Prefs.playlists = Prefs.playlists + Playlist(n, u)
                    refreshPlaylists()
                }
            }
            .setNegativeButton(t("Vazgeç"), null)
            .show()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(Palette.accent, dpf(12))
        setOnClickListener { onClick() }
    }

    companion object {
        private const val PICK_FILE = 7
        private const val REQ_BT = 11
        private const val EXTRA_ASK_LOCATION = "askLocation"
        const val EXTRA_FIX_INSTALL = "fixInstall"

        /** Araç ekranından: telefonda NewREX'i açıp konum iznini sorar. */
        fun askLocation(context: android.content.Context) {
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ASK_LOCATION, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }
}
