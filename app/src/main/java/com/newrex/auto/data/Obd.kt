// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.newrex.auto.DriveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * OBD-II: araca takılan Bluetooth ELM327 adaptörüyle motor beyninden canlı veri (hız, devir, motor sıcaklığı,
 * akü voltajı, yakıt, motor yükü) ve arıza kodları okunur. Adaptör telefonda bir kez eşleştirilip NewREX'te
 * seçilir; araç bağlanınca kendiliğinden bağlanır, kontak kapalıysa ara ara yeniden dener.
 * Veriler sadece telefonda kalır, hiçbir yere gönderilmez.
 */
object Obd {
    enum class State { OFF, CONNECTING, CONNECTED, NO_ECU, ERROR }

    data class Live(
        val speedKmh: Int? = null,
        val rpm: Int? = null,
        val coolantC: Int? = null,
        val volt: Float? = null,
        val fuelPct: Int? = null,
        val loadPct: Int? = null,
    )

    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    val state = MutableStateFlow(State.OFF)
    val live = MutableStateFlow(Live())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val io = Mutex()
    private var job: Job? = null
    private var socket: BluetoothSocket? = null
    private var appContext: Context? = null
    private var isCan = true
    /** Aracın desteklemediği veriler bir daha sorulmaz (bağlantı boyunca). */
    private val unsupported = HashSet<String>()

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    val configured get() = Prefs.obdMac.isNotBlank()

    /** Telefonla eşleşmiş Bluetooth cihazları; adı OBD/ELM gibi olanlar başta. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(context: Context): List<BluetoothDevice> {
        if (!hasPermission(context)) return emptyList()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        val list = runCatching { adapter.bondedDevices?.toList() }.getOrNull().orEmpty()
        val hint = Regex("obd|elm|vlink|v-link|vgate|icar|konnwei|scan|car", RegexOption.IGNORE_CASE)
        return list.sortedByDescending { runCatching { hint.containsMatchIn(it.name ?: "") }.getOrDefault(false) }
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (!configured || !hasPermission(context)) { state.value = State.OFF; return }
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun restart(context: Context) {
        val wasRunning = job?.isActive == true
        stop()
        if (wasRunning || DriveStateActive.active) start(context)
    }

    fun stop() {
        job?.cancel()
        job = null
        close()
        state.value = State.OFF
        live.value = Live()
        DriveState.setObdSpeed(null)
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop() {
        var wait = 5_000L
        while (isActive) {
            state.value = State.CONNECTING
            val result = runCatching { connect() }
            if (result.isSuccess) {
                state.value = State.CONNECTED
                wait = 5_000L
                runCatching { poll() }
            } else {
                state.value = if (result.exceptionOrNull() is NoEcu) State.NO_ECU else State.ERROR
            }
            close()
            live.value = Live()
            DriveState.setObdSpeed(null)
            delay(wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }

    private class NoEcu : IOException()

    @SuppressLint("MissingPermission")
    private suspend fun connect() {
        val ctx = appContext ?: throw IOException()
        val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException()
        if (!adapter.isEnabled) throw IOException()
        val device = adapter.getRemoteDevice(Prefs.obdMac)
        runCatching { adapter.cancelDiscovery() }
        val s = try {
            device.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: IOException) {
            // Bazı ucuz adaptörler güvenli bağlantıyı kabul etmez
            device.createInsecureRfcommSocketToServiceRecord(SPP).also { it.connect() }
        }
        socket = s
        unsupported.clear()
        send("ATZ", 4_000)
        delay(500)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) send(c)
        // İlk sorgu protokolü bulur (birkaç saniye sürebilir). Kontak kapalıysa araç cevap vermez.
        val first = send("0100", 12_000)
        if (!clean(first).contains("4100")) throw NoEcu()
        val proto = send("ATDPN").trim().uppercase()
        isCan = proto.lastOrNull()?.let { it in "6789ABC" } ?: true
    }

    private suspend fun poll() {
        var i = 0
        while (true) {
            val speed = pid("0D")?.let { it[0] }
            val rpm = pid("0C")?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4 }
            DriveState.setObdSpeed(speed?.let { it / 3.6f })
            var l = live.value.copy(speedKmh = speed, rpm = rpm)
            if (i % 5 == 0) {
                l = l.copy(
                    coolantC = pid("05")?.let { it[0] - 40 },
                    fuelPct = pid("2F")?.let { it[0] * 100 / 255 },
                    loadPct = pid("04")?.let { it[0] * 100 / 255 },
                    volt = if ("RV" in unsupported) null else
                        Regex("""(\d{1,2}\.\d)""").find(send("ATRV"))?.value?.toFloatOrNull().also { if (it == null) unsupported += "RV" },
                )
            }
            live.value = l
            i++
            delay(300)
        }
    }

    /** Mod 01 verisi: cevaptaki "41 <pid>" sonrasındaki baytlar; desteklenmiyorsa null. */
    private suspend fun pid(p: String): IntArray? {
        if (p in unsupported) return null
        val r = send("01$p")
        if (isNoData(r)) { unsupported += p; return null }
        val s = clean(r)
        val at = s.indexOf("41$p")
        if (at < 0) return null
        val hex = s.substring(at + 4)
        val bytes = (0 until hex.length / 2).map { hex.substring(it * 2, it * 2 + 2).toInt(16) }
        return bytes.toIntArray().takeIf { it.isNotEmpty() }
    }

    /** Kayıtlı arıza kodları (ör. P0133). Bağlı değilse null. */
    suspend fun readCodes(): List<String>? {
        if (state.value != State.CONNECTED) return null
        return runCatching { parseCodes(send("03", 8_000)) }.getOrNull()
    }

    /** Arıza kodlarını ve motor arıza lambasını siler. */
    suspend fun clearCodes(): Boolean {
        if (state.value != State.CONNECTED) return false
        return runCatching { clean(send("04", 8_000)).contains("44") }.getOrDefault(false)
    }

    private fun parseCodes(raw: String): List<String> {
        if (isNoData(raw)) return emptyList()
        val lines = raw.uppercase().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val frames = lines.filter { Regex("^[0-9A-F]:").containsMatchIn(it) }
        val chunks = if (frames.isNotEmpty()) listOf(frames.joinToString("") { clean(it.substringAfter(':')) })
            else lines.map { clean(it) }
        val codes = LinkedHashSet<String>()
        for (c in chunks) {
            val at = c.indexOf("43")
            if (at < 0) continue
            var hex = c.substring(at + 2)
            if (isCan && hex.length >= 2) {
                val n = hex.substring(0, 2).toInt(16)
                hex = hex.substring(2).take(n * 4)
            }
            for (k in 0 until hex.length / 4) {
                val b1 = hex.substring(k * 4, k * 4 + 2).toInt(16)
                val b2 = hex.substring(k * 4 + 2, k * 4 + 4).toInt(16)
                if (b1 == 0 && b2 == 0) continue
                val type = "PCBU"[b1 shr 6]
                codes += "$type${(b1 shr 4) and 3}${"%X".format(b1 and 0xF)}${"%02X".format(b2)}"
            }
        }
        return codes.toList()
    }

    private fun isNoData(r: String): Boolean {
        val u = r.uppercase()
        return "NO DATA" in u || "NODATA" in u || "UNABLE" in u || "ERROR" in u || "STOPPED" in u || u.trim() == "?"
    }

    /** Sadece onaltılık karakterler (SEARCHING... gibi yazılar atılır). */
    private fun clean(r: String): String =
        r.uppercase().replace("SEARCHING...", "").replace("BUS INIT...", "").filter { it in "0123456789ABCDEF" }

    /** Komutu gönderir, adaptörün ">" istemine kadar cevabı okur. */
    private suspend fun send(cmd: String, timeoutMs: Long = 2_500): String = io.withLock {
        withContext(Dispatchers.IO) {
            val s = socket ?: throw IOException()
            val out = s.outputStream
            val inp = s.inputStream
            while (inp.available() > 0) inp.read()
            out.write("$cmd\r".toByteArray())
            out.flush()
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (inp.available() > 0) {
                    val b = inp.read()
                    if (b < 0) throw IOException()
                    if (b.toChar() == '>') return@withContext sb.toString()
                    sb.append(b.toChar())
                } else delay(15)
            }
            if (sb.isEmpty()) throw IOException("timeout")
            sb.toString()
        }
    }

    private fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}

/** Araç oturumu açık mı (OBD yeniden başlatılırken bilinsin diye). */
object DriveStateActive {
    @Volatile var active = false
}
