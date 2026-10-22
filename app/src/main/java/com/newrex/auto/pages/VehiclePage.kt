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
import com.newrex.auto.data.Prefs
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
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Araç paneli: hız, yakıt, menzil, kilometre, yolculuk, park yeri ve bakım hatırlatıcıları. */
class VehiclePage(ui: CarUi) : Page(ui) {
    override val title = "Araç"

    private val tr = Locale("tr", "TR")

    override fun build(): View = with(ctx) {
        val gauges = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val speed = gauge(gauges, R.drawable.ic_speed, "Hız", big = true)
        val fuel = gauge(gauges, R.drawable.ic_gas, "Yakıt")
        val range = gauge(gauges, R.drawable.ic_car, "Menzil")
        val odo = gauge(gauges, R.drawable.ic_history, "Kilometre")
        val trip = gauge(gauges, R.drawable.ic_place, "Bu yolculuk")

        observe(DriveState.speedKmh) { v ->
            speed.first.text = v?.let { "%.0f".format(it) } ?: "—"
            speed.second.text = if (v == null) "km/s • bekleniyor" else if (DriveState.speedFromCar.value) "km/s • araçtan" else "km/s • GPS"
        }
        observe(VehicleData.fuelPercent) { v ->
            val battery = VehicleData.batteryPercent.value
            fuel.first.text = (v ?: battery)?.let { "%%%.0f".format(it) } ?: "—"
            fuel.second.text = when {
                v == null && battery == null -> "Araç paylaşmıyor"
                VehicleData.lowFuel.value -> "⚠ Yakıt az!"
                v == null -> "Batarya"
                else -> "Depo"
            }
            fuel.first.setTextColor(if (VehicleData.lowFuel.value) Palette.ORANGE else Palette.TEXT)
        }
        observe(VehicleData.rangeKm) { v ->
            range.first.text = v?.let { "%.0f".format(it) } ?: "—"
            range.second.text = if (v == null) "Araç paylaşmıyor" else "km gidebilirsin"
        }
        observe(VehicleData.odometerKm) { v ->
            odo.first.text = v?.let { String.format(tr, "%,.0f", it) } ?: "—"
            odo.second.text = if (v == null) "Araç paylaşmıyor" else "km toplam"
        }
        scope.launch {
            while (true) {
                val km = DriveState.tripMeters.value / 1000f
                val mins = ((System.currentTimeMillis() - DriveState.tripStart) / 60_000).coerceAtLeast(0)
                trip.first.text = "%.1f".format(km)
                trip.second.text = "km • ${if (mins >= 60) "${mins / 60} sa ${mins % 60} dk" else "$mins dk"}"
                delay(5000)
            }
        }

        val model = label("", 14f, Palette.TEXT_DIM)
        observe(VehicleData.model) { model.text = it?.let { m -> "🚗 $m" } ?: "" ; model.visibility = if (it == null) View.GONE else View.VISIBLE }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(12))
            addView(model, lp(MATCH, WRAP).margins(l = dp(4), b = dp(6)))
            addView(HorizontalScrollView(this@with).apply {
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                addView(gauges, lp(WRAP, WRAP))
            }, lp(MATCH, WRAP))
            addView(maintenancePanel(), lp(MATCH, WRAP).margins(t = dp(10)))
            addView(parkPanel(), lp(MATCH, WRAP).margins(t = dp(10)))
        }
        ScrollView(this).apply { addView(content) }
    }

    private fun <T> observe(flow: Flow<T>, block: (T) -> Unit) {
        scope.launch { flow.collect { block(it) } }
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
        row.addView(card, lp(0, WRAP, if (big) 1.3f else 1f).margins(r = dp(8)))
        value to sub
    }

    private fun maintenancePanel(): View = with(ctx) {
        val box = panel().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(R.drawable.ic_build, 18, Palette.accent), lp(dp(18), dp(18)).margins(r = dp(8)))
            addView(label("Bakım hatırlatıcı", 16f, bold = true))
        }, lp(MATCH, WRAP).margins(b = dp(8)))
        val due = Prefs.maintenanceDue()
        if (due.isEmpty()) {
            box.addView(label("Muayene, sigorta, bakım ve lastik tarihlerini telefondaki NewREX uygulamasından gir. " +
                "Zamanı yaklaşınca burada ve araç açılınca uyarı çıkar.", 13f, Palette.TEXT_DIM, lines = 3))
        }
        due.forEach { (m, days) ->
            val color = when { days < 0 -> Palette.RED; days <= 15 -> Palette.ORANGE; else -> Palette.GREEN }
            val text = when { days < 0 -> "${-days} gün gecikti"; days == 0L -> "Bugün!"; else -> "$days gün kaldı" }
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = rounded(Palette.withAlpha(color, 0x22), dpf(12))
                addView(label("${m.emoji}  ${m.title}", 15f), lp(0, WRAP, 1f))
                addView(label(text, 14f, color, bold = true))
            }, lp(MATCH, WRAP).margins(b = dp(6)))
        }
        box
    }

    private fun parkPanel(): View = with(ctx) {
        val spot = Prefs.parkedSpot
        val text = if (spot == null) "Araç kapanınca (Android Auto bağlantısı kesilince) bulunduğun yer kaydedilir. " +
            "Telefondaki NewREX uygulamasında \"Arabam nerede?\" kısmından haritada açabilirsin."
        else "Son park: " + SimpleDateFormat("d MMMM HH:mm", tr).format(Date(spot.time)) +
            " — telefondaki NewREX uygulamasından haritada açabilirsin."
        panel().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(icon(R.drawable.ic_place, 22, Palette.accent), lp(dp(22), dp(22)).margins(r = dp(10)))
            addView(label(text, 13f, Palette.TEXT_DIM, lines = 3), lp(0, WRAP, 1f))
        }
    }
}
