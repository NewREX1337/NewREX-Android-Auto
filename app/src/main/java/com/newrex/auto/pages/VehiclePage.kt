// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.view.Gravity
import android.view.View
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
    override val title = "Araç"

    private val tr = Locale("tr", "TR")
    private lateinit var lower: LinearLayout
    private var drivingBox: LinearLayout? = null

    override fun build(): View = with(ctx) {
        val gauges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val speed = gauge(gauges, R.drawable.ic_speed, "Hız", big = true)
        val fuel = gauge(gauges, R.drawable.ic_gas, "Yakıt / menzil")
        val odo = gauge(gauges, R.drawable.ic_car, "Kilometre")
        val trip = gauge(gauges, R.drawable.ic_place, "Bu yolculuk")
        val time = gauge(gauges, R.drawable.ic_history, "Yoldasın")
        val alt = gauge(gauges, R.drawable.ic_up, "Rakım / yön")

        val noPermission = "📍 Telefonda konum izni ver"
        observe(DriveState.speedKmh) { v ->
            speed.first.text = v?.let { "%.0f".format(it) } ?: "—"
            speed.second.text = when {
                v != null -> if (DriveState.speedFromCar.value) "km/s • araçtan" else "km/s • GPS"
                !DriveState.hasLocationPermission(ctx) -> noPermission
                else -> "km/s • GPS bekleniyor"
            }
            val limit = CarLog.speedLimit
            speed.first.setTextColor(if (limit > 0 && v != null && v > limit) Palette.RED else Palette.TEXT)
        }
        // İzin yoksa hız/yolculuk kartına dokununca telefonda izin sorulur
        listOf(speed, trip).forEach { (v, _) ->
            (v.parent as View).setOnClickListener {
                if (!DriveState.hasLocationPermission(ctx)) {
                    com.newrex.auto.MainActivity.askLocation(ctx.applicationContext)
                    ui.toast("Telefonunda konum iznini ver.", long = true)
                }
            }
        }

        // Saniyede bir değişmesi gerekmeyen değerler birkaç saniyede bir yenilenir
        scope.launch {
            while (true) {
                DriveState.ensureGps()
                val tripKm = DriveState.tripMeters.value / 1000f
                val start = DriveState.tripStart
                val mins = if (start > 0) ((System.currentTimeMillis() - start) / 60_000).coerceAtLeast(0) else null
                trip.first.text = String.format(tr, "%.1f km", tripKm)
                trip.second.text = if (!DriveState.hasLocationPermission(ctx)) noPermission else "GPS ile ölçülür"
                time.first.text = mins?.let { duration(it) } ?: "—"
                time.second.text = if (start > 0) "${SimpleDateFormat("HH:mm", tr).format(Date(start))}'den beri" else ""

                val carOdo = VehicleData.odometerKm.value
                val currentKm = carOdo ?: CarLog.estimatedOdometer(tripKm)
                odo.first.text = currentKm?.let { (if (carOdo == null) "~" else "") + String.format(tr, "%,.0f", it) } ?: "—"
                odo.second.text = when {
                    carOdo != null -> "km • araçtan"
                    currentKm != null -> "km • tahmini"
                    else -> "Telefonda km gir"
                }

                val pct = VehicleData.fuelPercent.value ?: VehicleData.batteryPercent.value
                val carRange = VehicleData.rangeKm.value
                val estRange = CarLog.fuelStats(currentKm).rangeKm
                fuel.first.text = when {
                    pct != null -> "%%%.0f".format(pct)
                    carRange != null -> "%.0f km".format(carRange)
                    estRange != null -> "~%.0f km".format(estRange)
                    else -> "—"
                }
                fuel.second.text = when {
                    VehicleData.lowFuel.value -> "⚠ Yakıt az!"
                    pct != null && carRange != null -> "%.0f km gidebilirsin".format(carRange)
                    pct != null -> "depo • araçtan"
                    carRange != null -> "menzil • araçtan"
                    estRange != null -> "menzil • tahmini"
                    else -> "Yakıt defterini doldur"
                }
                fuel.first.setTextColor(if (VehicleData.lowFuel.value) Palette.ORANGE else Palette.TEXT)

                alt.first.text = DriveState.altitude.value?.let { "${it.toInt()} m" } ?: "—"
                alt.second.text = DriveState.bearing.value?.let { "Yön: ${compass(it)}" } ?: "Yön: hareket edince"
                delay(3000)
            }
        }

        val model = label("", 14f, Palette.TEXT_DIM)
        observe(VehicleData.model) { model.text = it?.let { m -> "🚗 $m" } ?: ""; model.visibility = if (it == null) View.GONE else View.VISIBLE }

        lower = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
            addView(model, lp(MATCH, WRAP).margins(l = dp(4), b = dp(6)))
            addView(HorizontalScrollView(this@with).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(gauges, lp(WRAP, WRAP))
            }, lp(MATCH, WRAP))
            addView(lower, lp(MATCH, WRAP))
        }
        scope.launch { CarLog.changes.collect { buildLower() } }
        // Sürüş puanı canlı güncellensin
        scope.launch { while (true) { delay(10_000); refreshDriving() } }
        ScrollView(this).apply { addView(content) }
    }

    private fun <T> observe(flow: Flow<T>, block: (T) -> Unit) {
        scope.launch { flow.collect { block(it) } }
    }

    private fun duration(mins: Long) = if (mins >= 60) "${mins / 60} sa ${mins % 60} dk" else "$mins dk"

    private fun compass(deg: Float): String {
        val names = arrayOf("Kuzey", "Kuzeydoğu", "Doğu", "Güneydoğu", "Güney", "Güneybatı", "Batı", "Kuzeybatı")
        return names[(((deg % 360) + 360) % 360 / 45f + 0.5f).toInt() % 8]
    }

    private fun currentKm(): Float? =
        VehicleData.odometerKm.value ?: CarLog.estimatedOdometer(DriveState.tripMeters.value / 1000f)

    // ---- Alt bölümler ----

    private fun buildLower() = with(ctx) {
        lower.removeAllViews()
        listOf(drivingPanel(), speedAlertPanel(), fuelPanel(), maintenancePanel(), tripsPanel(), parkPanel()).forEach {
            lower.addView(it, lp(MATCH, WRAP).margins(t = dp(10)))
        }
    }

    private fun box(iconRes: Int, title: String): LinearLayout = with(ctx) {
        panel().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(iconRes, 18, Palette.accent), lp(dp(18), dp(18)).margins(r = dp(8)))
                addView(label(title, 16f, bold = true))
            }, lp(MATCH, WRAP).margins(b = dp(8)))
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
                    addView(label(value, 20f, if (i == 0) firstColor else Palette.TEXT, bold = true))
                    addView(label(caption, 12f, Palette.TEXT_DIM, lines = 2), lp(WRAP, WRAP).margins(t = dp(2)))
                }, lp(0, WRAP, 1f).margins(r = dp(8)))
            }
        }
    }

    private fun drivingPanel(): View {
        val b = box(R.drawable.ic_car, "Sürüş puanı (bu yolculuk)")
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
            "$score / 100" to "Ekonomik sürüş",
            "${TripStats.harshAccel.value}" to "Ani hızlanma",
            "${TripStats.harshBrake.value}" to "Sert fren",
            "%.0f".format(TripStats.maxKmh.value) to "En yüksek km/s",
            (avg?.let { "%.0f".format(it) } ?: "—") to "Ortalama km/s",
            firstColor = color,
        ))
        b.addView(label("Ani hızlanma ve sert frenler puanı düşürür; yumuşak sürüş yakıt tasarrufu sağlar.",
            12f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(8)))
    }

    private fun speedAlertPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_speed, "Hız uyarısı")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val current = CarLog.speedLimit
        listOf(0, 50, 70, 90, 110, 120, 130, 140).forEach { v ->
            row.addView(pill(if (v == 0) "Kapalı" else "$v", selected = v == current, heightDp = 40) {
                CarLog.speedLimit = v
            }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        b.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        // Uyarı sesi: dokununca seçilir ve bir kez dinletilir
        val sounds = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        com.newrex.auto.SpeedAlert.SOUNDS.forEachIndexed { i, name ->
            sounds.addView(pill("🔔 $name", selected = i == CarLog.speedSound, heightDp = 40) {
                CarLog.speedSound = i
                com.newrex.auto.SpeedAlert.preview(ctx, i)
            }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        b.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(sounds) },
            lp(MATCH, WRAP).margins(t = dp(8)))
        // Konuşan ses: "Dikkat! Hız sınırını aştın." — dokununca seçilir ve dinletilir
        val voices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        com.newrex.auto.SpeedAlert.VOICES.forEachIndexed { i, (name, _) ->
            voices.addView(pill("🗣 $name", selected = i == CarLog.speedVoice, heightDp = 40) {
                CarLog.speedVoice = i
                if (i > 0) com.newrex.auto.SpeedAlert.previewVoice(ctx, i)
            }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        b.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(voices) },
            lp(MATCH, WRAP).margins(t = dp(8)))
        b.addView(label(if (current == 0) "Seçtiğin hızı geçince arabanın hoparlöründen sesli uyarı ve ekranda büyük kırmızı uyarı çıkar."
            else "$current km/s geçilince uyarır. Hız GPS ile ölçüldüğünde birkaç km/s sapabilir.",
            12f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(8)))
        b
    }

    private fun fuelPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_gas, "Yakıt defteri")
        val s = CarLog.fuelStats(currentKm())
        if (CarLog.fuel().isEmpty()) {
            b.addView(label("Depoyu fullediğinde telefondaki NewREX uygulamasında \"Yakıt ve kilometre\" bölümüne " +
                "litre, tutar ve kilometreyi gir. Ortalama tüketim, km başı maliyet ve tahmini menzil burada çıkar.",
                13f, Palette.TEXT_DIM, lines = 4))
        } else {
            b.addView(stats(
                (s.consumption?.let { "%.1f".format(it) } ?: "—") to "L / 100 km",
                (s.costPerKm?.let { "%.2f ₺".format(it) } ?: "—") to "km başı",
                String.format(tr, "%,.0f ₺", s.thisMonth) to "Bu ay",
                (s.rangeKm?.let { "~%.0f km".format(it) } ?: "—") to "Tahmini menzil",
            ))
            if (s.consumption == null) b.addView(label("Ortalama için en az iki dolum ve 50 km yol gerekiyor.",
                12f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = dp(8)))
        }
        b
    }

    private fun maintenancePanel(): View = with(ctx) {
        val b = box(R.drawable.ic_build, "Bakım hatırlatıcı")
        CarLog.oilKmLeft(currentKm())?.let { left ->
            val color = when { left < 0 -> Palette.RED; left <= 1000 -> Palette.ORANGE; else -> Palette.GREEN }
            val text = if (left < 0) String.format(tr, "%,.0f km geçti", -left) else String.format(tr, "%,.0f km kaldı", left)
            b.addView(row("🛢️  Yağ değişimi", text, color), lp(MATCH, WRAP).margins(b = dp(6)))
        }
        val due = Prefs.maintenanceDue()
        if (due.isEmpty() && CarLog.oilChangeKm <= 0f) {
            b.addView(label("Muayene, sigorta, bakım ve lastik tarihlerini, son yağ değişimi kilometresini telefondaki " +
                "NewREX uygulamasından gir. Zamanı yaklaşınca burada ve araç açılınca uyarı çıkar.", 13f, Palette.TEXT_DIM, lines = 3))
        }
        due.forEach { (m, days) ->
            val color = when { days < 0 -> Palette.RED; days <= 15 -> Palette.ORANGE; else -> Palette.GREEN }
            val text = when { days < 0 -> "${-days} gün gecikti"; days == 0L -> "Bugün!"; else -> "$days gün kaldı" }
            b.addView(row("${m.emoji}  ${m.title}", text, color), lp(MATCH, WRAP).margins(b = dp(6)))
        }
        b
    }

    private fun row(title: String, value: String, color: Int): View = with(ctx) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Palette.withAlpha(color, 0x22), dpf(12))
            addView(label(title, 15f), lp(0, WRAP, 1f))
            addView(label(value, 14f, color, bold = true))
        }
    }

    private fun tripsPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_history, "Yolculuk geçmişi")
        val trips = CarLog.trips().take(6)
        if (trips.isEmpty()) {
            b.addView(label("Araç kapanınca (Android Auto bağlantısı kesilince) yolculuk buraya kaydedilir.",
                13f, Palette.TEXT_DIM, lines = 2))
        }
        val fmt = SimpleDateFormat("d MMM HH:mm", tr)
        trips.forEach { t ->
            b.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(0x14FFFFFF, dpf(12))
                addView(label(fmt.format(Date(t.start)), 14f, bold = true), lp(dp(120), WRAP))
                addView(label("%.1f km • %s".format(t.km, duration(((t.end - t.start) / 60_000).coerceAtLeast(0))), 14f), lp(0, WRAP, 1f))
                addView(label("ort ${t.avgKmh} • en çok ${t.maxKmh} km/s", 13f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(r = dp(12)))
                val c = when { t.score >= 85 -> Palette.GREEN; t.score >= 60 -> Palette.ORANGE; else -> Palette.RED }
                addView(label("${t.score}", 15f, c, bold = true))
            }, lp(MATCH, WRAP).margins(b = dp(6)))
        }
        b
    }

    private fun parkPanel(): View = with(ctx) {
        val b = box(R.drawable.ic_place, "Park")
        val lines = ArrayList<String>()
        if (!DriveState.driving.value && DriveState.stoppedSince > 0) {
            lines += "Şu an duruyorsun: ${duration((System.currentTimeMillis() - DriveState.stoppedSince) / 60_000)}"
        }
        val spot = Prefs.parkedSpot
        lines += if (spot == null) "Araç kapanınca bulunduğun yer kaydedilir; telefondaki \"Arabam nerede?\" bölümünden haritada açarsın."
        else "Son park yeri: " + SimpleDateFormat("d MMMM HH:mm", tr).format(Date(spot.time)) +
            " — telefondan haritada açabilir, otopark süresi için hatırlatıcı kurabilirsin."
        b.addView(label(lines.joinToString("\n"), 13f, Palette.TEXT_DIM, lines = 4))
        b
    }

    /** Büyük değer + alt yazı. Dönen çift: (değer, alt yazı). */
    private fun gauge(row: LinearLayout, iconRes: Int, title: String, big: Boolean = false): Pair<TextView, TextView> = with(ctx) {
        val value = label("—", if (big) 40f else 30f, bold = true).apply { if (big) neonText(12) }
        val sub = label("", 12f, Palette.TEXT_DIM)
        val card = panel().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumWidth = dp(if (big) 150 else 128)
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon(iconRes, 18, Palette.accent), lp(dp(18), dp(18)).margins(r = dp(6)))
                addView(label(title, 13f, Palette.TEXT_DIM, bold = true))
            })
            addView(value, lp(WRAP, WRAP).margins(t = dp(8)))
            addView(sub, lp(WRAP, WRAP).margins(t = dp(2)))
            if (big) background = rounded(Palette.withAlpha(Palette.accent, 0x33), dpf(18), dp(1), Palette.withAlpha(Palette.accent, 0x88))
        }
        // Genişlik 0 olunca kaydırılabilir satırda kartlar en küçük boyuta iniyor, "12.3 km" gibi yazılar kesiliyordu
        row.addView(card, lp(WRAP, WRAP, if (big) 1.3f else 1f).margins(r = dp(8)))
        value to sub
    }
}
