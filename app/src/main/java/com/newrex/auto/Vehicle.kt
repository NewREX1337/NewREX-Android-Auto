// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.Context
import android.location.Geocoder
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.EnergyLevel
import androidx.car.app.hardware.info.Mileage
import androidx.car.app.hardware.info.Model
import androidx.core.content.ContextCompat
import com.newrex.auto.data.CarLog
import com.newrex.auto.data.ParkedSpot
import com.newrex.auto.data.TripRecord
import com.newrex.auto.data.TripStats
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.WeatherApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Aracın paylaştığı bilgiler. Her araç her bilgiyi vermez; verilmeyenler null kalır ve ekranda
 * "araç paylaşmıyor" yazar. Bu bilgiler telefondan dışarı çıkmaz.
 */
object VehicleData {
    val model = MutableStateFlow<String?>(null)
    val fuelPercent = MutableStateFlow<Float?>(null)
    val batteryPercent = MutableStateFlow<Float?>(null)
    val rangeKm = MutableStateFlow<Float?>(null)
    val odometerKm = MutableStateFlow<Float?>(null)
    val lowFuel = MutableStateFlow(false)

    private var cleanup: (() -> Unit)? = null

    private fun <T> CarValue<T>.ok(): T? = if (status == CarValue.STATUS_SUCCESS) value else null

    fun start(carContext: CarContext) {
        stop()
        val cleanups = ArrayList<() -> Unit>()
        val info = runCatching { carContext.getCarService(CarHardwareManager::class.java).carInfo }.getOrNull() ?: return
        val ex = ContextCompat.getMainExecutor(carContext)

        runCatching {
            info.fetchModel(ex, OnCarDataAvailableListener<Model> { m ->
                model.value = listOfNotNull(m.manufacturer.ok(), m.name.ok(), m.year.ok()?.toString())
                    .joinToString(" ").ifBlank { null }
            })
        }
        runCatching {
            val l = OnCarDataAvailableListener<EnergyLevel> { e ->
                fuelPercent.value = e.fuelPercent.ok()
                batteryPercent.value = e.batteryPercent.ok()
                rangeKm.value = e.rangeRemainingMeters.ok()?.let { it / 1000f }
                lowFuel.value = e.energyIsLow.ok() == true
            }
            info.addEnergyLevelListener(ex, l)
            cleanups += { info.removeEnergyLevelListener(l) }
        }
        runCatching {
            val l = OnCarDataAvailableListener<Mileage> { m -> odometerKm.value = m.odometerMeters.ok()?.let { it / 1000f } }
            info.addMileageListener(ex, l)
            cleanups += { info.removeMileageListener(l) }
        }
        cleanup = { cleanups.forEach { runCatching { it() } } }
    }

    fun stop() {
        cleanup?.invoke()
        cleanup = null
    }
}

/** Android Auto bağlantısı kesilince yolculuğu (mesafe, süre, hızlar, sürüş puanı) geçmişe kaydeder. */
object TripSaver {
    fun save() {
        val start = DriveState.tripStart
        if (start == 0L) return
        val km = DriveState.tripMeters.value / 1000f
        CarLog.addTrip(TripRecord(
            start = start,
            end = System.currentTimeMillis(),
            km = km,
            movingMinutes = (TripStats.movingMs / 60_000).toInt(),
            maxKmh = TripStats.maxKmh.value.toInt(),
            harshAccel = TripStats.harshAccel.value,
            harshBrake = TripStats.harshBrake.value,
            score = TripStats.score(km),
        ))
    }
}

/**
 * Otopark süresi hatırlatıcısı: süre dolmadan 10 dakika önce (kısa sürelerde süre dolunca) telefonda
 * bildirim gösterir.
 */
object ParkTimer {
    private const val CHANNEL = "park_timer"

    fun endTime(context: Context): Long =
        context.getSharedPreferences("newrex_car", Context.MODE_PRIVATE).getLong("parkTimerEnd", 0L)
            .takeIf { it > System.currentTimeMillis() } ?: 0L

    fun start(context: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        context.getSharedPreferences("newrex_car", Context.MODE_PRIVATE).edit().putLong("parkTimerEnd", end).apply()
        val at = if (minutes > 20) end - 10 * 60_000L else end
        context.getSystemService(android.app.AlarmManager::class.java)
            .setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pending(context))
    }

    fun cancel(context: Context) {
        context.getSharedPreferences("newrex_car", Context.MODE_PRIVATE).edit().remove("parkTimerEnd").apply()
        context.getSystemService(android.app.AlarmManager::class.java).cancel(pending(context))
    }

    private fun pending(context: Context) = android.app.PendingIntent.getBroadcast(context, 0,
        android.content.Intent(context, ParkTimerReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)

    internal fun notify(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Otopark süresi",
            android.app.NotificationManager.IMPORTANCE_HIGH))
        val left = ((endTime(context) - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        val open = android.app.PendingIntent.getActivity(context, 0,
            android.content.Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        nm.notify(9, android.app.Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_parking)
            .setContentTitle(if (left > 0) "Otopark süren $left dakika sonra doluyor" else "Otopark süren doldu")
            .setContentText("Arabanın yerini NewREX'te \"Arabam nerede?\" bölümünden görebilirsin.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}

class ParkTimerReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) = ParkTimer.notify(context)
}

/** Android Auto bağlantısı kesilince (araç kapanınca) son konumu park yeri olarak kaydeder. */
object ParkSaver {
    fun save(context: Context) {
        val loc = DriveState.bestLocation(context) ?: return
        // Çok eski konum yanlış yeri gösterir
        if (System.currentTimeMillis() - loc.time > 30 * 60_000) return
        Prefs.parkedSpot = ParkedSpot(loc.latitude, loc.longitude, System.currentTimeMillis(), loc.accuracy)
    }
}

/** Hava durumu: konum telefonda kalır, servise yalnızca ~1 km'ye yuvarlanmış koordinat gider. */
object WeatherHub {
    val report = MutableStateFlow<WeatherApi.Report?>(null)
    val error = MutableStateFlow<String?>(null)
    private const val MAX_AGE_MS = 30 * 60_000L

    suspend fun refresh(context: Context, force: Boolean = false) {
        val current = report.value
        if (!force && current != null && System.currentTimeMillis() - current.time < MAX_AGE_MS) return
        val loc = DriveState.bestLocation(context)
        if (loc == null) {
            error.value = "Konum bulunamadı. Telefonda NewREX'e konum izni ver."
            return
        }
        try {
            val place = withContext(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    Geocoder(context, Locale("tr", "TR")).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                        ?.let { it.subAdminArea ?: it.locality ?: it.adminArea }
                }.getOrNull()
            }
            report.value = WeatherApi.fetch(loc.latitude, loc.longitude, place)
            error.value = null
        } catch (e: Exception) {
            if (report.value == null) error.value = "Hava durumu alınamadı. İnternet bağlantını kontrol et."
        }
    }
}
