// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/** Biten bir yolculuk (Android Auto bağlantısı süresince). */
data class TripRecord(
    val start: Long,
    val end: Long,
    val km: Float,
    val movingMinutes: Int,
    val maxKmh: Int,
    val harshAccel: Int,
    val harshBrake: Int,
    val score: Int,
) {
    val avgKmh: Int get() = if (movingMinutes > 0) (km / movingMinutes * 60).roundToInt() else 0

    fun toJson(): JSONObject = JSONObject().put("s", start).put("e", end).put("km", km.toDouble()).put("mm", movingMinutes)
        .put("max", maxKmh).put("ha", harshAccel).put("hb", harshBrake).put("sc", score)

    companion object {
        fun fromJson(o: JSONObject) = TripRecord(o.optLong("s"), o.optLong("e"), o.optDouble("km").toFloat(),
            o.optInt("mm"), o.optInt("max"), o.optInt("ha"), o.optInt("hb"), o.optInt("sc", 100))
    }
}

/** Yakıt alımı. Depo her seferinde fullenmiş kabul edilir (tüketim hesabı böyle doğru çıkar). */
data class FuelEntry(val time: Long, val liters: Float, val price: Float, val km: Float) {
    fun toJson(): JSONObject = JSONObject().put("t", time).put("l", liters.toDouble()).put("p", price.toDouble()).put("km", km.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = FuelEntry(o.optLong("t"), o.optDouble("l").toFloat(), o.optDouble("p").toFloat(),
            o.optDouble("km").toFloat())
    }
}

/**
 * Cihazsız araç defteri: yolculuklar, yakıt alımları, tahmini kilometre ve yağ bakımı.
 * Her şey sadece telefonda saklanır. Kilometre ve menzil GPS'e ve girilen değerlere dayanan tahminlerdir.
 */
object CarLog {
    private const val MAX_TRIPS = 30
    private lateinit var sp: SharedPreferences

    /** Kayıtlar değişince artar; ekranlar bunu dinleyip yenilenir. */
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("newrex_car", Context.MODE_PRIVATE)
    }

    private fun bump() { changes.value++ }

    // ---- Ayarlar ----

    /** Hız uyarısı sınırı (km/s); 0 = kapalı. */
    var speedLimit: Int
        get() = sp.getInt("speedLimit", 0)
        set(v) { sp.edit().putInt("speedLimit", v).apply(); bump() }

    /** Hız uyarısının sesi (SpeedAlert.SOUNDS sırası). */
    var speedSound: Int
        get() = sp.getInt("speedSound", 0)
        set(v) { sp.edit().putInt("speedSound", v).apply(); bump() }

    /** Sesten sonra konuşan ses (SpeedAlert.VOICES sırası); 0 = konuşmasın. */
    var speedVoice: Int
        get() = sp.getInt("speedVoiceIx", 1)
        set(v) { sp.edit().putInt("speedVoiceIx", v).apply(); bump() }

    /** Depo hacmi (litre); menzil tahmini için. Toyota C-HR hibrit ~43 L. */
    var tankLiters: Float
        get() = sp.getFloat("tank", 43f)
        set(v) { sp.edit().putFloat("tank", v).apply(); bump() }

    var oilChangeKm: Float
        get() = sp.getFloat("oilKm", 0f)
        set(v) { sp.edit().putFloat("oilKm", v).apply(); bump() }

    var oilIntervalKm: Float
        get() = sp.getFloat("oilInterval", 10_000f)
        set(v) { sp.edit().putFloat("oilInterval", v).apply(); bump() }

    // ---- Kilometre tahmini ----

    /** Bütün yolculuklarda GPS ile ölçülen toplam mesafe. */
    private var gpsTotalKm: Float
        get() = sp.getFloat("gpsTotal", 0f)
        set(v) = sp.edit().putFloat("gpsTotal", v).apply()

    /** Kullanıcının girdiği kilometre ve o andaki GPS toplamı. */
    fun setOdometer(km: Float) {
        sp.edit().putFloat("odoBase", km).putFloat("odoGpsAtBase", gpsTotalKm).apply()
        bump()
    }

    /** Tahmini kilometre: girilen değer + o zamandan beri GPS ile gidilen yol. Girilmediyse null. */
    fun estimatedOdometer(liveTripKm: Float = 0f): Float? {
        val base = sp.getFloat("odoBase", 0f)
        if (base <= 0f) return null
        return base + (gpsTotalKm - sp.getFloat("odoGpsAtBase", 0f)) + liveTripKm
    }

    // ---- Yolculuklar ----

    fun trips(): List<TripRecord> = read("trips") { TripRecord.fromJson(it) }

    fun addTrip(t: TripRecord) {
        gpsTotalKm += t.km
        // Çok kısa bağlantılar (garajda açıp kapama) geçmişi doldurmasın
        if (t.km >= 0.3f) write("trips", (listOf(t) + trips()).take(MAX_TRIPS).map { it.toJson() })
        bump()
    }

    // ---- Yakıt ----

    fun fuel(): List<FuelEntry> = read("fuel") { FuelEntry.fromJson(it) }.sortedBy { it.km }

    fun addFuel(e: FuelEntry) {
        write("fuel", (fuel() + e).map { it.toJson() })
        // Yakıt alırken girilen kilometre, tahmini kilometreyi de düzeltir
        if (e.km > 0f) setOdometer(e.km) else bump()
    }

    fun removeFuel(e: FuelEntry) {
        write("fuel", fuel().filterNot { it == e }.map { it.toJson() })
        bump()
    }

    class FuelStats(
        /** L/100 km */
        val consumption: Float?,
        /** TL/km */
        val costPerKm: Float?,
        val thisMonth: Float,
        /** Depoda kalan tahmini yakıtla gidilebilecek km. */
        val rangeKm: Float?,
    )

    fun fuelStats(currentKm: Float?): FuelStats {
        val list = fuel()
        val now = java.time.LocalDate.now()
        val thisMonth = list.filter {
            val d = java.time.Instant.ofEpochMilli(it.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            d.year == now.year && d.month == now.month
        }.sumOf { it.price.toDouble() }.toFloat()
        // İlk dolumdan sonraki dolumlar, aradaki yolda harcanan yakıttır
        val withKm = list.filter { it.km > 0f }
        var consumption: Float? = null
        var costPerKm: Float? = null
        if (withKm.size >= 2) {
            val dist = withKm.last().km - withKm.first().km
            if (dist > 50f) {
                val used = withKm.drop(1)
                consumption = used.sumOf { it.liters.toDouble() }.toFloat() / dist * 100f
                costPerKm = used.sumOf { it.price.toDouble() }.toFloat() / dist
            }
        }
        val last = withKm.lastOrNull()
        val range = if (consumption != null && consumption > 0f && last != null && currentKm != null) {
            val left = tankLiters - (currentKm - last.km).coerceAtLeast(0f) * consumption / 100f
            (left / consumption * 100f).coerceAtLeast(0f)
        } else null
        return FuelStats(consumption, costPerKm, thisMonth, range)
    }

    /** Yağ değişimine kalan km (kilometre biliniyorsa). */
    fun oilKmLeft(currentKm: Float?): Float? {
        if (currentKm == null || oilChangeKm <= 0f) return null
        return oilChangeKm + oilIntervalKm - currentKm
    }

    private fun <T> read(key: String, parse: (JSONObject) -> T): List<T> {
        val arr = runCatching { JSONArray(sp.getString(key, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun write(key: String, list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }
}

/**
 * Süren yolculuğun canlı istatistikleri. Hız örneklerinden en yüksek hız, hareket süresi ve
 * ani hızlanma / sert fren sayısı çıkarılır; bunlardan 100 üzerinden ekonomik sürüş puanı hesaplanır.
 */
object TripStats {
    private const val HARSH_ACCEL = 3.0f   // m/s² (~11 km/s her saniye)
    private const val HARSH_BRAKE = -3.5f  // m/s²

    val maxKmh = MutableStateFlow(0f)
    val harshAccel = MutableStateFlow(0)
    val harshBrake = MutableStateFlow(0)
    var movingMs = 0L
        private set

    private var lastSpeed = -1f
    private var lastTime = 0L
    private var lastHarsh = 0L

    fun reset() {
        maxKmh.value = 0f; harshAccel.value = 0; harshBrake.value = 0
        movingMs = 0; lastSpeed = -1f; lastTime = 0; lastHarsh = 0
    }

    /** Hız örneği (m/s). */
    fun onSpeed(mps: Float, time: Long) {
        val kmh = mps * 3.6f
        if (kmh > maxKmh.value && kmh < 260f) maxKmh.value = kmh
        if (lastTime > 0) {
            val dt = (time - lastTime) / 1000f
            if (dt in 0.2f..5f && lastSpeed > 1f) movingMs += (dt * 1000).toLong()
            // Örnekler arası 0,7–3 sn ise ivme anlamlı; aynı olay iki kez sayılmasın diye 3 sn bekle
            if (dt in 0.7f..3f && lastSpeed >= 0f && time - lastHarsh > 3000) {
                val a = (mps - lastSpeed) / dt
                if (a > HARSH_ACCEL) { harshAccel.value++; lastHarsh = time }
                if (a < HARSH_BRAKE) { harshBrake.value++; lastHarsh = time }
            }
        }
        lastSpeed = mps
        lastTime = time
    }

    /** 100 üzerinden puan: her 10 km'deki ani hareket başına 8 puan düşer. */
    fun score(km: Float): Int {
        val events = harshAccel.value + harshBrake.value
        if (events == 0) return 100
        val per10 = events / (km / 10f).coerceAtLeast(1f)
        return (100 - per10 * 8).roundToInt().coerceIn(0, 100)
    }
}
