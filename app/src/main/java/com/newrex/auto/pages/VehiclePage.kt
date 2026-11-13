// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.DriveState
import com.newrex.auto.R
import com.newrex.auto.VehicleData
import com.newrex.auto.data.CarLog
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.TripStats
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.neonText
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Araç paneli: hız, yakıt/menzil, kilometre, yolculuk, rakım ve yön; sürüş puanı, hız uyarısı,
 * yakıt defteri, bakım, yolculuk geçmişi ve park bilgisi. Araç paylaşmayan bilgiler GPS ve
 * telefonda girilen kayıtlardan tahmin edilir ("~" ile gösterilir).
 */
class VehiclePage(ui: CarUi) : Page(ui) {
    override val title = t("Araç")

    private val tr = com.newrex.auto.I18n.locale()
    private var drivingBox: LinearLayout? = null
    private lateinit var pager: com.newrex.auto.ui.VerticalPager
    private lateinit var dots: LinearLayout

    /** Kaydırılan bölüm sayfaları: (simge, başlık, içerik üreten). İlk sayfa hız göstergesidir. */
    private val sections: List<Triple<Int, String, () -> View>> by lazy {
        listOf(
            Triple(R.drawable.ic_history, t("Yolculuk geçmişi")) { tripsPanel() },
            Triple(R.drawable.ic_build, t("Motor · OBD-II")) { obdPanel() },
        )
    }
    private val sectionBoxes = ArrayList<FrameLayout>()

    /**
     * Sayfa sayfa aşağı kaydırılır: önce büyük hız göstergesi ve özet, sonra her konu kendi tam ekran sayfasında
     * (sürüş puanı, hız uyarısı, yakıt, bakım, yolculuklar, park). Sağdaki noktalar hangi sayfada olunduğunu
     * gösterir; dokununca o sayfaya gidilir.
     */
    override fun build(): View = with(ctx) {
        pager = com.newrex.auto.ui.VerticalPager(this)
        // Kaydırınca sayfa değişmesin (harita ve araç kaydırılıyor); sayfalar sağ alttaki küçük tuşlarla açılır
        pager.locked = true
        pager.addView(dashboard(), FrameLayout.LayoutParams(MATCH, MATCH))
        sections.forEach { (iconRes, title, _) ->
            val box = FrameLayout(this)
            sectionBoxes += box
            pager.addView(sectionPage(iconRes, title, box), FrameLayout.LayoutParams(MATCH, MATCH))
        }
        dots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        refreshDots(0)
        pager.onPage = { refreshDots(it) }
        scope.launch { CarLog.changes.collect { fillSections() } }
        scope.launch {
            kotlinx.coroutines.flow.combine(com.newrex.auto.data.Obd.state, com.newrex.auto.data.Obd.live) { a, b -> a to b }
                .collect { refreshObd() }
        }
        // Sürüş puanı canlı güncellensin
        scope.launch { while (true) { delay(10_000); refreshDriving() } }
        FrameLayout(this).apply {
            addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(dots, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).margins(r = dp(10), b = dp(8)))
        }
    }

    /** Sağ altta küçük sayfa tuşları (kaydırma kapalı): bulunulan sayfa dışındakiler gösterilir. */
    private fun refreshDots(current: Int) = with(ctx) {
        dots.removeAllViews()
        val names = listOf(t("Gösterge")) + sections.map { it.second }
        names.forEachIndexed { i, name ->
            if (i == current) return@forEachIndexed
            dots.addView(label(name + if (i > current) "  ›" else "", 12f, Palette.TEXT_DIM).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = rounded(0x99101218.toInt(), dpf(13), 1, 0x26FFFFFF)
                setOnClickListener { pager.goTo(i) }
            }, lp(WRAP, dp(26)).margins(l = dp(6)))
        }
    }

    private fun fillSections() {
        sections.forEachIndexed { i, (_, _, make) ->
            val box = sectionBoxes[i]
            box.removeAllViews()
            box.addView(make(), FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // ---- Motor · OBD-II ----

    private var obdStatus: TextView? = null
    private val obdValues = HashMap<String, TextView>()
    private var obdCodesBox: LinearLayout? = null
    private var obdCodes: List<String>? = null
    private var obdClearArmed = false

    private fun obdPanel(): View = with(ctx) {
        obdValues.clear()
        val O = com.newrex.auto.data.Obd
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (!O.configured) {
                addView(panel().apply {
                    setPadding(dp(20), dp(18), dp(20), dp(18))
                    addView(label(t("OBD-II adaptörü seçilmedi"), 18f, bold = true))
                    addView(label(t("Arabanın OBD soketine bir Bluetooth ELM327 adaptörü tak, telefonun Bluetooth ayarlarından eşleştir, sonra telefonda NewREX'i açıp \"OBD-II adaptör\" bölümünden seç. Motor devri, motor sıcaklığı, akü voltajı, yakıt ve arıza kodları burada görünür."),
                        14f, Palette.TEXT_DIM, lines = 6), lp(MATCH, WRAP).margins(t = dp(8)))
                })
                obdStatus = null
                return@apply
            }
            obdStatus = label("", 15f, Palette.TEXT_DIM)
            addView(obdStatus, lp(MATCH, WRAP).margins(b = dp(12)))
            val tiles = listOf(
                "rpm" to t("Motor devri"), "cool" to t("Motor sıcaklığı"), "volt" to t("Akü"),
                "speed" to t("Hız"), "fuel" to t("Yakıt"), "load" to t("Motor yükü"),
            )
            tiles.chunked(3).forEachIndexed { r, row ->
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.HORIZONTAL
                    row.forEachIndexed { c, (key, name) ->
                        addView(panel().apply {
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                            addView(label(name, 13f, Palette.TEXT_DIM))
                            val v = label("—", 30f, bold = true)
                            obdValues[key] = v
                            addView(v, lp(MATCH, WRAP).margins(t = dp(2)))
                        }, lp(0, WRAP, 1f).margins(l = if (c > 0) dp(10) else 0))
                    }
                }, lp(MATCH, WRAP).margins(t = if (r > 0) dp(10) else 0))
            }
            // Arıza kodları
            addView(panel().apply {
                setPadding(dp(18), dp(14), dp(18), dp(14))
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(label(t("Arıza kodları"), 17f, bold = true), lp(0, WRAP, 1f))
                    addView(pill(t("Oku"), heightDp = 40) { readObdCodes() }, lp(WRAP, dp(40)).margins(r = dp(8)))
                    addView(pill(if (obdClearArmed) t("Emin misin? Tekrar bas") else t("Sil"), heightDp = 40, selected = obdClearArmed) { clearObdCodes() },
                        lp(WRAP, dp(40)))
                })
                obdCodesBox = LinearLayout(this@with).apply { orientation = LinearLayout.VERTICAL }
                addView(obdCodesBox, lp(MATCH, WRAP).margins(t = dp(8)))
            }, lp(MATCH, WRAP).margins(t = dp(14)))
            fillObdCodes()
            refreshObd()
        }
    }

    private fun refreshObd() {
        val O = com.newrex.auto.data.Obd
        val st = obdStatus ?: return
        st.text = when (O.state.value) {
            com.newrex.auto.data.Obd.State.CONNECTED -> t("Bağlı: {0}", Prefs.obdName.ifBlank { "OBD-II" })
            com.newrex.auto.data.Obd.State.CONNECTING -> t("Adaptöre bağlanıyor…")
            com.newrex.auto.data.Obd.State.NO_ECU -> t("Adaptör bağlı ama araç cevap vermiyor. Kontak açık mı?")
            com.newrex.auto.data.Obd.State.ERROR -> t("Adaptöre bağlanılamadı. Takılı ve telefonla eşleşmiş mi? Tekrar deneniyor…")
            com.newrex.auto.data.Obd.State.OFF -> if (!O.hasPermission(ctx)) t("Telefonda NewREX'e Bluetooth izni ver.") else t("Kapalı")
        }
        val l = O.live.value
        fun set(k: String, v: String?) { obdValues[k]?.text = v ?: "—" }
        set("rpm", l.rpm?.let { "$it" })
        set("cool", l.coolantC?.let { "$it °C" })
        set("volt", l.volt?.let { String.format(tr, "%.1f V", it) })
        set("speed", l.speedKmh?.let { "$it km/s" })
        set("fuel", l.fuelPct?.let { "%$it" })
        set("load", l.loadPct?.let { "%$it" })
        obdValues["cool"]?.setTextColor(if ((l.coolantC ?: 0) >= 108) Palette.ORANGE else Palette.TEXT)
        obdValues["volt"]?.setTextColor(if (l.volt != null && l.volt < 11.8f) Palette.ORANGE else Palette.TEXT)
    }

    private fun fillObdCodes() = with(ctx) {
        val box = obdCodesBox ?: return@with
        box.removeAllViews()
        val codes = obdCodes
        val text = when {
            codes == null -> t("Okumak için \"Oku\"ya bas. Silmek arıza lambasını söndürür ama sorunu çözmez.")
            codes.isEmpty() -> t("Kayıtlı arıza kodu yok.")
            else -> codes.joinToString("   ") + "\n" + t("Kodların anlamını internette aratabilirsin (ör. \"{0} anlamı\").", codes.first())
        }
        box.addView(label(text, if (codes.isNullOrEmpty()) 14f else 18f, if (codes.isNullOrEmpty()) Palette.TEXT_DIM else Palette.TEXT,
            bold = !codes.isNullOrEmpty(), lines = 4))
    }

    private fun readObdCodes() {
        if (com.newrex.auto.data.Obd.state.value != com.newrex.auto.data.Obd.State.CONNECTED) { ui.toast(t("Önce adaptör bağlanmalı")); return }
        ui.toast(t("Arıza kodları okunuyor…"))
        scope.launch {
            val r = com.newrex.auto.data.Obd.readCodes()
            if (r == null) ui.toast(t("Okunamadı, tekrar dene")) else { obdCodes = r; fillObdCodes() }
        }
    }

    private fun clearObdCodes() {
        if (com.newrex.auto.data.Obd.state.value != com.newrex.auto.data.Obd.State.CONNECTED) { ui.toast(t("Önce adaptör bağlanmalı")); return }
        if (!obdClearArmed) {
            obdClearArmed = true
            fillSections()
            scope.launch { delay(5_000); if (obdClearArmed) { obdClearArmed = false; fillSections() } }
            return
        }
        obdClearArmed = false
        scope.launch {
            val ok = com.newrex.auto.data.Obd.clearCodes()
            ui.toast(if (ok) t("Arıza kodları silindi") else t("Silinemedi. Kontak açık, motor kapalıyken tekrar dene."))
            if (ok) obdCodes = emptyList()
            fillSections()
        }
    }

    /** Bir konunun tam ekran sayfası: büyük başlık, altında içerik (uzunsa sayfa içinde kayar). */
    private fun sectionPage(iconRes: Int, title: String, box: FrameLayout): View = with(ctx) {
        ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), dp(18), dp(60), dp(18))
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon(iconRes, 28, Palette.accent), lp(dp(28), dp(28)).margins(r = dp(12)))
                    addView(label(title, 24f, bold = true))
                }, lp(MATCH, WRAP).margins(b = dp(14)))
                addView(box, lp(MATCH, WRAP))
            }, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    // Özet sayfasındaki canlı değerler (sayfa yeniden kurulunca yenileri atanır)
    private var sumTrip: Pair<TextView, TextView>? = null
    private var sumTime: Pair<TextView, TextView>? = null
    private var sumFuel: Pair<TextView, TextView>? = null
    private var sumOdo: Pair<TextView, TextView>? = null
    private var sumScore: Pair<TextView, TextView>? = null
    private var sumAlt: Pair<TextView, TextView>? = null

    /**
     * İlk sayfa: dijital gösterge paneli. Solda hız kadranı, sağda P/D ve sürüş puanı, altta menzil, saat, konum ve
     * dış sıcaklık. Ortada kullanıcının seçtiği: 360° dönen araç ya da canlı harita (üstteki düğmeyle değişir).
     * Araç modunda ekranın neresinden yana kaydırılırsa araç döner.
     */
    private fun dashboard(): View = with(ctx) {
        com.newrex.auto.data.CarFrames.load(applicationContext)
        val cluster = com.newrex.auto.ui.ClusterView(this)
        val center = FrameLayout(this)
        var car: com.newrex.auto.ui.CarSpinView? = null
        var map: com.newrex.auto.ui.DashMap? = null

        fun showCenter() {
            center.removeAllViews()
            map?.destroy(); map = null; car = null
            if (Prefs.dashCenter == 1) {
                map = com.newrex.auto.ui.DashMap(this).also { center.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
            } else if (com.newrex.auto.data.CarImage.has(this)) {
                center.addView(com.newrex.auto.ui.CarPhotoView(this), FrameLayout.LayoutParams(MATCH, MATCH))
            } else {
                car = com.newrex.auto.ui.CarSpinView(this).also { center.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
            }
        }
        showCenter()
        scope.launch { com.newrex.auto.data.CarFrames.version.collect { car?.invalidate() } }
        scope.launch {
            var first = true
            com.newrex.auto.data.CarImage.version.collect { if (!first && Prefs.dashCenter != 1) showCenter(); first = false }
        }

        // Sağ üst: paket ve renk seçimi
        val garageBtn = FrameLayout(this)
        fun refreshTopRight() {
            garageBtn.removeAllViews()
            garageBtn.addView(if (Prefs.dashCenter == 1)
                pill(t("Ara"), com.newrex.auto.R.drawable.ic_search, heightDp = 34) {
                    ui.showKeyboard(t("Adres ya da yer ara")) { q -> ui.push(MapSearchPage(ui, q)) }
                }
            else pill(t("Aracım"), com.newrex.auto.R.drawable.ic_car, heightDp = 34) { ui.push(GaragePage(ui)) })
        }
        refreshTopRight()
        // Araç / Harita seçimi
        val toggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(0x99101218.toInt(), dpf(18), dp(1), 0x1FFFFFFF)
        }
        fun refreshToggle() {
            toggle.removeAllViews()
            listOf(t("Araç"), t("Harita")).forEachIndexed { i, name ->
                val sel = Prefs.dashCenter == i
                toggle.addView(label(name, 13f, if (sel) Palette.TEXT else Palette.TEXT_DIM, bold = sel).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), 0)
                    background = rounded(if (sel) Palette.withAlpha(com.newrex.auto.ui.gaugeAccent(), 0x66) else 0, dpf(15))
                    setOnClickListener {
                        if (Prefs.dashCenter != i) { Prefs.dashCenter = i; showCenter(); refreshToggle(); refreshTopRight() }
                    }
                }, lp(WRAP, dp(30)))
            }
        }
        refreshToggle()
        // Telefondan hedef paylaşılınca harita kendiliğinden açılır
        scope.launch {
            Prefs.destination.collect {
                if (Prefs.dashCenter == 1 && map == null) { showCenter(); refreshToggle(); refreshTopRight() }
            }
        }

        observe(DriveState.speedKmh) { v ->
            cluster.speedCaption = when {
                v != null -> "km/s"
                !DriveState.hasLocationPermission(ctx) -> t("Konum izni ver")
                else -> t("GPS bekleniyor")
            }
            cluster.speed = v
        }
        observe(DriveState.driving) { cluster.driving = it }
        scope.launch {
            while (true) {
                DriveState.ensureGps()
                val tripKm = DriveState.tripMeters.value / 1000f
                val start = DriveState.tripStart
                val mins = if (start > 0) ((System.currentTimeMillis() - start) / 60_000).coerceAtLeast(0) else null
                cluster.score = TripStats.score(tripKm)
                val items = dialItems(tripKm)
                val w = com.newrex.auto.WeatherHub.report.value
                cluster.strip = arrayOf(
                    if (items[0].first != "—") items[0].first else items[1].first + " km",
                    SimpleDateFormat("HH:mm", tr).format(Date()),
                    w?.place ?: "",
                    w?.let { (if (it.temp > 0) "+" else "") + "${it.temp}°C" } ?: "",
                )
                refreshSummary(tripKm, start, mins)
                delay(3000)
            }
        }

        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        object : FrameLayout(this) {
            private var downX = 0f
            private var downY = 0f
            private var lastX = 0f
            private var spinning = false

            private fun horizontal(e: android.view.MotionEvent) =
                kotlin.math.abs(e.x - downX) > slop && kotlin.math.abs(e.x - downX) > kotlin.math.abs(e.y - downY)

            override fun onInterceptTouchEvent(e: android.view.MotionEvent): Boolean {
                val c = car ?: return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; spinning = false }
                    android.view.MotionEvent.ACTION_MOVE -> if (!spinning && horizontal(e)) {
                        spinning = true; lastX = e.x; c.dragStart()
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return spinning
            }

            override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
                val c = car ?: return false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; return true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (!spinning && horizontal(e)) { spinning = true; lastX = e.x; c.dragStart() }
                        if (spinning) { c.dragBy(e.x - lastX); lastX = e.x }
                    }
                    android.view.MotionEvent.ACTION_UP -> { if (spinning) c.dragEnd(); spinning = false }
                    android.view.MotionEvent.ACTION_CANCEL -> { if (spinning) c.dragEnd(cancel = true); spinning = false }
                }
                return true
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val w = r - l; val h = b - t
                val side = dp(44)   // sağdaki sayfa noktaları
                val cw = w - side
                cluster.layout(0, 0, cw, h)
                val cr = cluster.centerRect(cw, h)
                center.layout(cr.left.toInt(), cr.top.toInt() + dp(8), cr.right.toInt(), cr.bottom.toInt())
                val tw = toggle.measuredWidth; val th = toggle.measuredHeight
                toggle.layout((cw - tw) / 2, dp(6), (cw + tw) / 2, dp(6) + th)
                val gw = garageBtn.measuredWidth; val gh = garageBtn.measuredHeight
                garageBtn.layout(cw - gw - dp(8), dp(6), cw - dp(8), dp(6) + gh)
            }

            override fun onMeasure(ws: Int, hs: Int) {
                super.onMeasure(ws, hs)
                val un = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                toggle.measure(un, un)
                garageBtn.measure(un, MeasureSpec.makeMeasureSpec(dp(34), MeasureSpec.EXACTLY))
                val w = measuredWidth - dp(44); val h = measuredHeight
                val cr = cluster.centerRect(w, h)
                center.measure(MeasureSpec.makeMeasureSpec(cr.width().toInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cr.height().toInt() - dp(8), MeasureSpec.EXACTLY))
                cluster.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }

            override fun onDetachedFromWindow() {
                map?.destroy()
                super.onDetachedFromWindow()
            }
        }.apply {
            addView(center)
            addView(cluster)
            addView(toggle)
            addView(garageBtn)
        }
    }


    private fun refreshSummary(tripKm: Float, start: Long, mins: Long?) {
        val noPermission = t("📍 Telefonda konum izni ver")
        sumTrip?.let { (v, s) ->
            v.text = String.format(tr, "%.1f km", tripKm)
            s.text = if (!DriveState.hasLocationPermission(ctx)) noPermission else t("GPS ile ölçülür")
        }
        sumTime?.let { (v, s) ->
            v.text = mins?.let { duration(it) } ?: "—"
            s.text = if (start > 0) "${SimpleDateFormat("HH:mm", tr).format(Date(start))}'den beri" else ""
        }
        val carOdo = VehicleData.odometerKm.value
        val currentKm = carOdo ?: CarLog.estimatedOdometer(tripKm)
        sumOdo?.let { (v, s) ->
            v.text = currentKm?.let { (if (carOdo == null) "~" else "") + String.format(tr, "%,.0f", it) } ?: "—"
            s.text = when {
                carOdo != null -> t("km • araçtan")
                currentKm != null -> "km • tahmini"
                else -> t("Telefonda km gir")
            }
        }
        sumFuel?.let { (v, s) ->
            val pct = VehicleData.fuelPercent.value ?: VehicleData.batteryPercent.value
            val carRange = VehicleData.rangeKm.value
            val estRange = CarLog.fuelStats(currentKm).rangeKm
            v.text = when {
                pct != null -> "%%%.0f".format(pct)
                carRange != null -> "%.0f km".format(carRange)
                estRange != null -> "~%.0f km".format(estRange)
                else -> "—"
            }
            s.text = when {
                VehicleData.lowFuel.value -> t("⚠ Yakıt az!")
                pct != null && carRange != null -> t("%.0f km gidebilirsin").format(carRange)
                pct != null -> t("depo • araçtan")
                carRange != null -> t("menzil • araçtan")
                estRange != null -> "menzil • tahmini"
                else -> t("Yakıt defterini doldur")
            }
            v.setTextColor(if (VehicleData.lowFuel.value) Palette.ORANGE else Palette.TEXT)
        }
        sumScore?.let { (v, s) ->
            val sc = TripStats.score(tripKm)
            v.text = "$sc / 100"
            v.setTextColor(when { sc >= 85 -> Palette.GREEN; sc >= 60 -> Palette.ORANGE; else -> Palette.RED })
            s.text = t("Ekonomik sürüş")
        }
        sumAlt?.let { (v, s) ->
            v.text = DriveState.altitude.value?.let { "${it.toInt()} m" } ?: "—"
            s.text = DriveState.bearing.value?.let { t("Yön: {0}", compass(it)) } ?: t("Yön: hareket edince")
        }
    }

    /** Kadranın altındaki üç bilgi: yakıt/menzil, toplam km, yağ değişimine kalan. */
    private fun dialItems(tripKm: Float): List<Triple<String, String, Int>> {
        val carOdo = VehicleData.odometerKm.value
        val km = carOdo ?: CarLog.estimatedOdometer(tripKm)
        val pct = VehicleData.fuelPercent.value ?: VehicleData.batteryPercent.value
        val carRange = VehicleData.rangeKm.value
        val estRange = CarLog.fuelStats(km).rangeKm
        val fuel = when {
            carRange != null -> String.format(tr, "%,.0f km", carRange)
            estRange != null -> String.format(tr, "~%,.0f km", estRange)
            pct != null -> "%%%.0f".format(pct)
            else -> "—"
        }
        val fuelColor = if (VehicleData.lowFuel.value) Palette.ORANGE else 0xFFFFFFFF.toInt()
        val odo = km?.let { (if (carOdo == null) "~" else "") + String.format(tr, "%,.0f", it) } ?: "—"
        val oil = CarLog.oilKmLeft(km)
        val oilText = oil?.let { if (it < 0) String.format(tr, "-%,.0f", -it) else String.format(tr, "%,.0f", it) } ?: "—"
        val oilColor = when { oil == null -> 0xFFFFFFFF.toInt(); oil < 0 -> Palette.RED; oil <= 1000 -> Palette.ORANGE; else -> Palette.GREEN }
        return listOf(
            Triple(fuel, t("MENZİL"), fuelColor),
            Triple(odo, "KM", 0xFFFFFFFF.toInt()),
            Triple(oilText, t("YAĞA KM"), oilColor),
        )
    }

    /** Özet: altı büyük kutu, iki satır; her biri tek bir bilgi. */
    private fun summaryPanel(): View = with(ctx) {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        sumTrip = null; sumTime = null; sumFuel = null; sumOdo = null
        sumScore = statCard(row1, R.drawable.ic_speed, t("Sürüş puanı"))
        sumAlt = statCard(row1, R.drawable.ic_up, t("Rakım / yön"))
        grid.addView(row1, lp(MATCH, WRAP))
        val model = label("", 13f, Palette.TEXT_DIM)
        VehicleData.model.value?.let { model.text = "🚗 $it"; grid.addView(model, lp(WRAP, WRAP).margins(t = dp(12))) }
        val tripKm = DriveState.tripMeters.value / 1000f
        val start = DriveState.tripStart
        refreshSummary(tripKm, start, if (start > 0) ((System.currentTimeMillis() - start) / 60_000).coerceAtLeast(0) else null)
        grid
    }

    /** Özet kutusu: simge + başlık, büyük değer, küçük açıklama. Dönen çift: (değer, açıklama). */
    private fun statCard(row: LinearLayout, iconRes: Int, title: String): Pair<TextView, TextView> = with(ctx) {
        val value = label("—", 40f, bold = true).apply {
            setAutoSizeTextTypeUniformWithConfiguration(16, 40, 1, android.util.TypedValue.COMPLEX_UNIT_DIP)
        }
        val sub = label("", 14f, Palette.TEXT_DIM)
        row.addView(panel().apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(150)
            setPadding(dp(22), dp(16), dp(16), dp(16))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(iconRes, 18, Palette.accent), lp(dp(18), dp(18)).margins(r = dp(8)))
                addView(label(title, 14f, Palette.TEXT_DIM, bold = true))
            })
            addView(value, lp(MATCH, dp(50)).margins(t = dp(10)))
            addView(sub, lp(WRAP, WRAP).margins(t = dp(4)))
        }, lp(0, WRAP, 1f).margins(l = if (row.childCount > 0) dp(14) else 0))
        value to sub
    }

    private fun <T> observe(flow: Flow<T>, block: (T) -> Unit) {
        scope.launch { flow.collect { block(it) } }
    }

    private fun duration(mins: Long) = if (mins >= 60) "${mins / 60} sa ${mins % 60} dk" else t("{0} dk", mins)

    private fun compass(deg: Float): String {
        val names = arrayOf(t("Kuzey"), t("Kuzeydoğu"), t("Doğu"), t("Güneydoğu"), t("Güney"), t("Güneybatı"), t("Batı"), t("Kuzeybatı"))
        return names[(((deg % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
    }

    private fun currentKm(): Float? =
        VehicleData.odometerKm.value ?: CarLog.estimatedOdometer(DriveState.tripMeters.value / 1000f)

    // ---- Bölümler ----

    /** Bölüm kutusu; başlık sayfanın kendisinde büyük yazılı (ilk satır boş yer tutucu, içerik sonra eklenir). */
    @Suppress("UNUSED_PARAMETER")
    private fun box(iconRes: Int, title: String): LinearLayout = with(ctx) {
        panel().apply {
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(View(this@with), lp(MATCH, 0))
        }
    }

    /** Değer + açıklama şeklinde küçük istatistik kutuları yan yana. İlk değerin rengi [firstColor]. */
    private fun stats(vararg items: Pair<String, String>, firstColor: Int = Palette.TEXT): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEachIndexed { i, (value, caption) ->
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(0x14FFFFFF, dpf(12))
                    addView(label(value, 20f, if (i == 0) firstColor else Palette.TEXT, bold = true).apply {
                        setAutoSizeTextTypeUniformWithConfiguration(11, 20, 1, android.util.TypedValue.COMPLEX_UNIT_DIP)
                    }, lp(MATCH, dp(24)))
                    addView(label(caption, 12f, Palette.TEXT_DIM, lines = 2), lp(WRAP, WRAP).margins(t = dp(2)))
                }, lp(0, WRAP, 1f).margins(r = dp(8)))
            }
        }
    }

    private fun drivingPanel(): View {
        val b = box(R.drawable.ic_car, t("Sürüş puanı (bu yolculuk)"))
        drivingBox = b
        refreshDriving()
        return b
    }

    private fun refreshDriving() = with(ctx) {
        val b = drivingBox ?: return@with
        while (b.childCount > 1) b.removeViewAt(1)
        val km = DriveState.tripMeters.value / 1000f
        val score = TripStats.score(km)
        val movingMin = TripStats.movingMs / 60_000f
        val avg = if (movingMin > 1f) km / movingMin * 60f else null
        val color = when { score >= 85 -> Palette.GREEN; score >= 60 -> Palette.ORANGE; else -> Palette.RED }
        b.addView(stats(
            "$score / 100" to t("Ekonomik sürüş"),
            "${TripStats.harshAccel.value}" to t("Ani hızlanma"),
            "${TripStats.harshBrake.value}" to t("Sert fren"),
            "%.0f".format(TripStats.maxKmh.value) to t("En yüksek km/s"),
            (avg?.let { "%.0f".format(it) } ?: "—") to t("Ortalama km/s"),
            firstColor = color,
        ))
        b.addView(label(t("Ani hızlanma ve sert frenler puanı düşürür; yumuşak sürüş yakıt tasarrufu sağlar."),
            12f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(8)))
    }

    private fun speedAlertPanel(): View = with(ctx) {
        val current = CarLog.speedLimit
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // Solda büyük levha: seçili sınır
        wrap.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(android.widget.ImageView(this@with).apply {
                setImageDrawable(com.newrex.auto.ui.SpeedSign(current.takeIf { it > 0 }, false))
            }, lp(dp(150), dp(150)))
            addView(label(if (current > 0) t("{0} km/s üstünde uyarır", current) else t("Uyarı kapalı"), 16f, bold = true).apply {
                gravity = Gravity.CENTER
            }, lp(WRAP, WRAP).margins(t = dp(10)))
        }, lp(dp(220), WRAP))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(label(t("SINIR"), 13f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.15f })
        val signs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0, 50, 70, 90, 110, 120, 130, 140).forEach { v ->
            signs.addView(android.widget.ImageView(this).apply {
                setImageDrawable(com.newrex.auto.ui.SpeedSign(v.takeIf { it > 0 }, v == current))
                background = com.newrex.auto.ui.pressable(rounded(0, dpf(36)), dpf(36))
                setOnClickListener { CarLog.speedLimit = v }
            }, lp(dp(72), dp(72)).margins(r = dp(6)))
        }
        right.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(signs) },
            lp(MATCH, WRAP).margins(t = dp(8)))

        right.addView(label(t("UYARI SESİ"), 13f, Palette.TEXT_DIM, bold = true).apply { letterSpacing = 0.15f },
            lp(WRAP, WRAP).margins(t = dp(18)))
        val sounds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        com.newrex.auto.SpeedAlert.SOUNDS.forEachIndexed { i, name ->
            val sel = i == CarLog.speedSound
            val r = dpf(22)
            sounds.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(18), 0)
                background = com.newrex.auto.ui.pressable(rounded(if (sel) Palette.accent else 0x1AFFFFFF, r,
                    if (sel) 0 else dp(1), 0x26FFFFFF), r)
                addView(icon(R.drawable.ic_play, 16, if (sel) Palette.TEXT else Palette.TEXT_DIM), lp(dp(16), dp(16)).margins(r = dp(8)))
                addView(label(name, 15f, if (sel) Palette.TEXT else Palette.TEXT_DIM, bold = sel))
                setOnClickListener {
                    CarLog.speedSound = i
                    com.newrex.auto.SpeedAlert.play(ctx, i)
                }
            }, lp(WRAP, dp(44)).margins(r = dp(8)))
        }
        right.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sounds) },
            lp(MATCH, WRAP).margins(t = dp(8)))
        right.addView(label(t("Sınır geçilince arabanın hoparlöründen sesli uyarı ve ekranda kırmızı şerit çıkar. GPS ile ölçülen hız birkaç km/s sapabilir."),
            13f, Palette.TEXT_FAINT, lines = 3), lp(MATCH, WRAP).margins(t = dp(14)))
        wrap.addView(right, lp(0, WRAP, 1f).margins(l = dp(20)))
        wrap
    }

    private fun fuelPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_gas, t("Yakıt defteri"))
        val s = CarLog.fuelStats(currentKm())
        if (CarLog.fuel().isEmpty()) {
            b.addView(label(t("Depoyu fullediğinde telefondaki NewREX uygulamasında \"Yakıt ve kilometre\" bölümüne ") +
                t("litre, tutar ve kilometreyi gir. Ortalama tüketim, km başı maliyet ve tahmini menzil burada çıkar."),
                13f, Palette.TEXT_DIM, lines = 4))
        } else {
            b.addView(stats(
                (s.consumption?.let { "%.1f".format(it) } ?: "—") to "L / 100 km",
                (s.costPerKm?.let { "%.2f ₺".format(it) } ?: "—") to t("km başı"),
                String.format(tr, "%,.0f ₺", s.thisMonth) to t("Bu ay"),
                (s.rangeKm?.let { "~%.0f km".format(it) } ?: "—") to t("Tahmini menzil"),
            ))
            if (s.consumption == null) b.addView(label(t("Ortalama için en az iki dolum ve 50 km yol gerekiyor."),
                12f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(8)))
        }
        b
    }

    private fun maintenancePanel(): View = with(ctx) {
        val b = box(R.drawable.ic_build, t("Bakım hatırlatıcı"))
        val due = Prefs.maintenanceDue()
        if (due.isEmpty()) {
            b.addView(label(t("Muayene, sigorta, bakım ve lastik tarihlerini telefondaki NewREX uygulamasından gir. Zamanı yaklaşınca burada ve araç açılınca uyarı çıkar."),
                15f, Palette.TEXT_DIM, lines = 3))
        }
        due.forEach { (m, days) ->
            val color = when { days < 0 -> Palette.RED; days <= 15 -> Palette.ORANGE; else -> Palette.GREEN }
            val text = when { days < 0 -> t("{0} gün gecikti", -days); days == 0L -> t("Bugün!"); else -> t("{0} gün kaldı", days) }
            b.addView(row(m.title, text, color), lp(MATCH, WRAP).margins(b = dp(8)))
        }
        b
    }

    private fun row(title: String, value: String, color: Int): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0x14FFFFFF, dpf(14))
            addView(View(this@with).apply { background = rounded(color, dpf(4)) }, lp(dp(6), dp(28)).margins(r = dp(14)))
            addView(label(title, 18f, bold = true), lp(0, WRAP, 1f))
            addView(label(value, 17f, color, bold = true))
        }
    }

    /** Yolculuk geçmişinde açık sayfa (her sayfada [TRIPS_PER_PAGE] yolculuk). */
    private var tripPage = 0
    private val TRIPS_PER_PAGE = 10

    private fun tripsPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_history, t("Yolculuk geçmişi"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        b.addView(list, lp(MATCH, WRAP))
        fillTrips(list)
        b
    }

    private fun fillTrips(list: LinearLayout): Unit = with(ctx) {
        list.removeAllViews()
        val all = CarLog.trips()
        if (all.isEmpty()) {
            list.addView(label(t("Araç kapanınca (Android Auto bağlantısı kesilince) yolculuk buraya kaydedilir."),
                13f, Palette.TEXT_DIM, lines = 2))
            return
        }
        val pages = (all.size + TRIPS_PER_PAGE - 1) / TRIPS_PER_PAGE
        tripPage = tripPage.coerceIn(0, pages - 1)
        val trips = all.drop(tripPage * TRIPS_PER_PAGE).take(TRIPS_PER_PAGE)
        val fmt = SimpleDateFormat("d MMM HH:mm", tr)
        trips.forEach { t ->
            list.addView(tripCard(t, fmt), lp(MATCH, WRAP).margins(b = dp(8)))
        }
        if (pages > 1) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (tripPage > 0) addView(pill(t("‹ Yeni yolculuklar"), heightDp = 40) { tripPage--; fillTrips(list) },
                    lp(WRAP, dp(40)).margins(r = dp(12)))
                addView(label(t("Sayfa {0} / {1}  •  {2} yolculuk", tripPage + 1, pages, all.size), 14f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(r = dp(12)))
                if (tripPage < pages - 1) addView(pill(t("Eski yolculuklar ›"), heightDp = 40) { tripPage++; fillTrips(list) },
                    lp(WRAP, dp(40)))
            }, lp(MATCH, WRAP).margins(t = dp(4)))
        }
    }

    /** Bir yolculuğun ayrıntılı kartı: üstte tarih, mesafe, süre, puan; altta sürüş olayları ve risk. */
    private fun tripCard(t: com.newrex.auto.data.TripRecord, fmt: SimpleDateFormat): View = with(ctx) {
        val scoreColor = when { t.score >= 85 -> Palette.GREEN; t.score >= 60 -> Palette.ORANGE; else -> Palette.RED }
        val riskText = when (t.risk) { 2 -> t("Yüksek risk"); 1 -> t("Orta risk"); else -> t("Düşük risk") }
        val riskColor = when (t.risk) { 2 -> Palette.RED; 1 -> Palette.ORANGE; else -> Palette.GREEN }
        fun metric(value: String, caption: String, color: Int = Palette.TEXT) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(value, 18f, color, bold = true))
            addView(label(caption, 12f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(2)))
        }
        fun count(v: Int) = if (v < 0) "—" else "$v"
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(0x14FFFFFF, dpf(18), dp(1), 0x14FFFFFF)
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Puan halkası
                addView(FrameLayout(this@with).apply {
                    background = rounded(Palette.withAlpha(scoreColor, 0x22), dpf(26), dp(3), scoreColor)
                    addView(label("${t.score}", 19f, scoreColor, bold = true).apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(MATCH, MATCH))
                }, lp(dp(52), dp(52)).margins(r = dp(16)))
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(fmt.format(Date(t.start)), 19f, bold = true))
                    addView(label("%.1f km   ·   %s   ·   ".format(t.km, duration(((t.end - t.start) / 60_000).coerceAtLeast(0))) +
                        t("ort {0} • en çok {1} km/s", t.avgKmh, t.maxKmh), 15f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(3)))
                }, lp(0, WRAP, 1f))
                addView(label(riskText, 14f, riskColor, bold = true).apply {
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    background = rounded(Palette.withAlpha(riskColor, 0x26), dpf(14))
                })
            })
            addView(View(this@with).apply { setBackgroundColor(0x14FFFFFF) }, lp(MATCH, 1).margins(t = dp(12), b = dp(10)))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                val items = listOf(
                    metric(count(t.harshAccel), t("Ani hızlanma"), if (t.harshAccel > 0) Palette.ORANGE else Palette.TEXT),
                    metric(count(t.harshBrake), t("Sert fren"), if (t.harshBrake > 0) Palette.ORANGE else Palette.TEXT),
                    metric(count(t.panicBrake), t("Panik fren"), if (t.panicBrake > 0) Palette.RED else Palette.TEXT),
                    metric(duration(t.movingMinutes.toLong()), t("Hareket")),
                    metric(duration(t.idleMinutes.toLong()), t("Bekleme")),
                )
                items.forEach { addView(it, lp(0, WRAP, 1f)) }
            })
        }
    }

    private fun parkPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_place, t("Park"))
        val lines = ArrayList<String>()
        if (!DriveState.driving.value && DriveState.stoppedSince > 0) {
            lines += t("Şu an duruyorsun: {0}", duration((System.currentTimeMillis() - DriveState.stoppedSince) / 60_000))
        }
        val spot = Prefs.parkedSpot
        lines += if (spot == null) t("Araç kapanınca bulunduğun yer kaydedilir; telefondaki \"Arabam nerede?\" bölümünden haritada açarsın.")
        else "Son park yeri: " + SimpleDateFormat("d MMMM HH:mm", tr).format(Date(spot.time)) +
            t(" — telefondan haritada açabilir, otopark süresi için hatırlatıcı kurabilirsin.")
        b.addView(label(lines.joinToString("\n"), 13f, Palette.TEXT_DIM, lines = 4))
        b
    }
}
