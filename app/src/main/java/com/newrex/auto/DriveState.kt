// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.Speed
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Araç hareket ediyor mu? Önce aracın kendi hız bilgisi, yoksa telefonun GPS hızı kullanılır.
 * Hareket halinde video ve web görüntüsü gizlenir, ses devam eder.
 */
object DriveState {
    private const val DRIVING_ABOVE_MS = 2.5f   // ~9 km/s
    private const val PARKED_BELOW_MS = 0.6f    // ~2 km/s

    val driving = MutableStateFlow(false)

    /** Sadece test (debug) sürümünde adb ile sürüşü taklit etmek için. */
    var forced: Boolean? = null
        set(v) { field = v; update() }

    private var carSpeed: Float? = null
    private var gpsSpeed: Float? = null
    private var stopListening: (() -> Unit)? = null

    fun start(carContext: CarContext) {
        stop()
        val cleanups = ArrayList<() -> Unit>()

        try {
            val info = carContext.getCarService(CarHardwareManager::class.java).carInfo
            val executor = ContextCompat.getMainExecutor(carContext)
            val listener = OnCarDataAvailableListener<Speed> { speed ->
                val value = speed.rawSpeedMetersPerSecond
                carSpeed = if (value.status == CarValue.STATUS_SUCCESS) value.value else null
                update()
            }
            info.addSpeedListener(executor, listener)
            cleanups += { info.removeSpeedListener(listener) }
        } catch (_: Throwable) {
            // Araç hız bilgisi vermiyor; GPS'e güveniyoruz
        }

        startGps(carContext)?.let { cleanups += it }
        stopListening = { cleanups.forEach { runCatching { it() } } }
    }

    fun stop() {
        stopListening?.invoke()
        stopListening = null
        carSpeed = null
        gpsSpeed = null
    }

    @SuppressLint("MissingPermission")
    private fun startGps(context: Context): (() -> Unit)? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                gpsSpeed = if (location.hasSpeed()) location.speed else null
                update()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            val cleanup: () -> Unit = { lm.removeUpdates(listener) }
            cleanup
        } catch (_: Exception) {
            null
        }
    }

    private fun update() {
        forced?.let { driving.value = it; return }
        val speed = carSpeed ?: gpsSpeed ?: return
        // Eşik arası boşluk, trafikte dur-kalk sırasında görüntünün sürekli yanıp sönmesini önler
        if (speed > DRIVING_ABOVE_MS) driving.value = true
        else if (speed < PARKED_BELOW_MS) driving.value = false
    }
}
