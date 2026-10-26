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

    /** Anlık hız (km/s); araçtan ya da GPS'ten. */
    val speedKmh = MutableStateFlow<Float?>(null)
    val speedFromCar = MutableStateFlow(false)

    /** Bu yolculukta gidilen mesafe (GPS ile ölçülür) ve başlangıç zamanı. */
    val tripMeters = MutableStateFlow(0f)
    var tripStart = 0L
        private set

    /** GPS rakımı (m) ve hareket yönü (derece); bilinmiyorsa null. */
    val altitude = MutableStateFlow<Double?>(null)
    val bearing = MutableStateFlow<Float?>(null)

    /** Aracın en son durduğu an (park süresi için); hareket ederken 0. */
    var stoppedSince = 0L
        private set

    /** Son GPS konumu; park yeri ve hava durumu için. */
    var lastLocation: Location? = null
        private set

    /** Sadece test (debug) sürümünde adb ile sürüşü taklit etmek için. */
    var forced: Boolean? = null
        set(v) { field = v; update() }

    private var carSpeed: Float? = null
    private var gpsSpeed: Float? = null
    private var stopListening: (() -> Unit)? = null
    private var gpsCleanup: (() -> Unit)? = null
    private var appContext: Context? = null

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * GPS dinlenmiyorsa (ör. konum izni araç bağlandıktan sonra verildiyse) dinlemeye başlar.
     * İzin verildiği an hız ve yolculuk ölçümü yeniden başlamadan çalışır.
     */
    fun ensureGps() {
        if (gpsCleanup != null) return
        val c = appContext ?: return
        gpsCleanup = startGps(c)
    }

    /** [context] bir CarContext ise aracın hız bilgisi de dinlenir; değilse (park uygulaması) sadece GPS. */
    fun start(context: Context) {
        stop()
        tripMeters.value = 0f
        tripStart = System.currentTimeMillis()
        stoppedSince = tripStart
        com.newrex.auto.data.TripStats.reset()
        val cleanups = ArrayList<() -> Unit>()

        if (context is CarContext) try {
            val info = context.getCarService(CarHardwareManager::class.java).carInfo
            val executor = ContextCompat.getMainExecutor(context)
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

        appContext = context.applicationContext
        ensureGps()
        stopListening = { cleanups.forEach { runCatching { it() } } }
    }

    fun stop() {
        stopListening?.invoke()
        stopListening = null
        gpsCleanup?.let { runCatching { it() } }
        gpsCleanup = null
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
                if (location.hasAltitude()) altitude.value = location.altitude
                // Yön sadece hareket halindeyken anlamlı
                if (location.hasBearing() && (gpsSpeed ?: 0f) > 1.5f) bearing.value = location.bearing
                val prev = lastLocation
                // Duruyorken GPS sapmaları mesafeye eklenmesin
                if (prev != null && location.accuracy < 30f && (gpsSpeed ?: 0f) > PARKED_BELOW_MS) {
                    val d = prev.distanceTo(location)
                    if (d in 3f..500f) tripMeters.value += d
                }
                if (prev == null || location.accuracy < 50f || location.accuracy <= prev.accuracy) lastLocation = location
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

    /** GPS dinlenmiyorsa telefonun bildiği son konum. */
    @SuppressLint("MissingPermission")
    fun bestLocation(context: Context): Location? {
        lastLocation?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000) return it }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return lastLocation
        val lm = context.getSystemService(LocationManager::class.java) ?: return lastLocation
        val known = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        return (known + listOfNotNull(lastLocation)).maxByOrNull { it.time }
    }

    private fun update() {
        val speed0 = carSpeed ?: gpsSpeed
        speedKmh.value = speed0?.let { it * 3.6f }
        speedFromCar.value = carSpeed != null
        forced?.let { driving.value = it; return }
        val speed = speed0 ?: return
        com.newrex.auto.data.TripStats.onSpeed(speed, android.os.SystemClock.elapsedRealtime())
        // Eşik arası boşluk, trafikte dur-kalk sırasında görüntünün sürekli yanıp sönmesini önler
        if (speed > DRIVING_ABOVE_MS) { driving.value = true; stoppedSince = 0L }
        else if (speed < PARKED_BELOW_MS) {
            if (driving.value || stoppedSince == 0L) stoppedSince = System.currentTimeMillis()
            driving.value = false
        }
    }
}
