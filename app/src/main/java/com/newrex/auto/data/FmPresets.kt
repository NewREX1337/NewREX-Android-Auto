// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bir FM frekansına yerleştirilmiş internet radyosu. [freq] MHz × 10 (ör. 1000 = 100.0 MHz).
 * Hazır olanların yayın adresi ilk açılışta Radio Browser'da [search] ile bulunur.
 */
data class FmPreset(
    val freq: Int,
    val title: String,
    val url: String? = null,
    val image: String? = null,
    val search: String? = null,
    val builtIn: Boolean = false,
)

/** FM ekranındaki frekans → istasyon eşleşmeleri. Sadece telefonda saklanır. */
object FmPresets {
    const val MIN = 875
    const val MAX = 1080

    /**
     * Hazır frekanslar. Kaynak: Milliyet "Radyo Frekansları 2026" (13.01.2026) ve Wikipedia "List of radio
     * stations in Turkey". İstanbul ve Ankara'da sadece iki kaynağın aynı frekansı verdiği istasyonlar alındı;
     * İzmir için tek kaynak (Milliyet) var. İnternet yayını Radio Browser'da bulunamayanlar listede yok. Kullanıcı ☆ ile kendi kayıtlarını ekleyebilir.
     */
    val CITIES: Map<String, List<Pair<Int, String>>> = linkedMapOf(
        "İstanbul" to listOf(
            890 to "Joy Türk",
            892 to "Alem FM",
            898 to "Show Radyo",
            900 to "Radyo Viva",
            904 to "Habertürk Radyo",
            908 to "Süper FM",
            920 to "Kral FM",
            962 to "Radyo Eksen",
            972 to "Metro FM",
            984 to "Best FM",
            998 to "Power Türk",
            1000 to "Power FM",
            1004 to "Radyo Fenomen",
            1006 to "Joy FM",
            1028 to "NTV Radyo",
            1040 to "Radyo D",
            1044 to "Bizim Radyo",
            1046 to "Radyo 7",
            1060 to "Pal Station",
            1070 to "Radyo Seymen",
            1072 to "Radyo Spor",
        ),
        "Ankara" to listOf(
            877 to "Pal FM",
            880 to "TRT FM",
            886 to "Baba Radyo",
            888 to "Show Radyo",
            898 to "Radyo 7",
            904 to "Habertürk Radyo",
            908 to "Süper FM",
            914 to "Pal Nostalji",
            936 to "Radyo Viva",
            938 to "Virgin Radio",
            953 to "Slow Türk",
            964 to "A Spor",
            972 to "Metro FM",
            983 to "Best FM",
            988 to "Radyo Fenomen",
            993 to "Pal Station",
            1000 to "Power FM",
            1018 to "Kral Pop",
            1024 to "Kral FM",
            1040 to "Radyo D",
            1044 to "TGRT FM",
            1047 to "NTV Radyo",
            1053 to "Alem FM",
            1060 to "Joy Türk",
            1065 to "Joy FM",
            1069 to "Power Türk",
        ),
        "İzmir" to listOf(
            877 to "Slow Türk",
            893 to "Alem FM",
            896 to "Show Radyo",
            908 to "Süper FM",
            912 to "TRT FM",
            920 to "TGRT FM",
            945 to "Power Pop",
            957 to "NTV Radyo",
            962 to "Kral FM",
            972 to "Metro FM",
            977 to "Joy FM",
            980 to "Radyo Fenomen",
            984 to "Best FM",
            993 to "Pal Station",
            997 to "Habertürk Radyo",
            1000 to "Power FM",
            1013 to "Radyo 7",
            1031 to "Virgin Radio",
            1040 to "Radyo D",
            1050 to "Power Türk",
            1055 to "Radyo Viva",
            1060 to "Joy Türk",
            1063 to "Kral Pop",
            1065 to "Radyo Seymen",
        ),
    )

    /** Şehir merkezleri: konuma en yakın şehir otomatik seçilir. */
    private val CENTERS = mapOf("İstanbul" to (41.01 to 28.98), "Ankara" to (39.93 to 32.86), "İzmir" to (38.42 to 27.14))

    var city: String
        get() = sp.getString("city", null)?.takeIf { it in CITIES } ?: "İstanbul"
        set(v) { sp.edit().putString("city", v).putBoolean("cityChosen", true).apply(); resolved.clear(); changes.value++ }

    /** Kullanıcı şehri elle seçmediyse konuma göre en yakın şehri seçer (150 km içindeyse). */
    fun autoCity(lat: Double, lon: Double) {
        if (sp.getBoolean("cityChosen", false)) return
        val nearest = CENTERS.minByOrNull { (_, c) ->
            val dLat = c.first - lat
            val dLon = (c.second - lon) * kotlin.math.cos(Math.toRadians(lat))
            dLat * dLat + dLon * dLon
        } ?: return
        val km = kotlin.math.sqrt((nearest.value.first - lat).let { it * it } +
            ((nearest.value.second - lon) * kotlin.math.cos(Math.toRadians(lat))).let { it * it }) * 111
        if (km < 150 && nearest.key != city) { sp.edit().putString("city", nearest.key).apply(); resolved.clear(); changes.value++ }
    }

    private val DEFAULTS: List<FmPreset>
        get() = CITIES[city].orEmpty().map { (f, n) -> FmPreset(f, n, search = n, builtIn = true) }

    private lateinit var sp: SharedPreferences
    private val resolved = HashMap<Int, MediaEntry>()
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("newrex_fm", Context.MODE_PRIVATE)
    }

    var lastFreq: Int
        get() = sp.getInt("last", 1000)
        set(v) = sp.edit().putInt("last", v).apply()

    fun format(freq: Int) = "${freq / 10}.${freq % 10}"

    private fun norm(t: String) = t.normName()

    private fun user(): List<FmPreset> {
        val arr = runCatching { JSONArray(sp.getString("presets_$city", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull {
            runCatching {
                val o = arr.getJSONObject(it)
                FmPreset(o.getInt("f"), o.optString("t"), o.optString("u"), o.optString("i").ifBlank { null })
            }.getOrNull()
        }
    }

    /** Kullanıcının kayıtları hazır frekansların önüne geçer. */
    fun all(): List<FmPreset> {
        val mine = user()
        val taken = mine.map { it.freq }.toSet()
        return (mine + DEFAULTS.filter { it.freq !in taken }).sortedBy { it.freq }
    }

    fun at(freq: Int): FmPreset? = all().firstOrNull { it.freq == freq }

    fun save(freq: Int, e: MediaEntry) {
        val list = user().filterNot { it.freq == freq } + FmPreset(freq, e.title, e.url, e.image)
        write(list)
        resolved.remove(freq)
    }

    fun remove(freq: Int) {
        write(user().filterNot { it.freq == freq })
        resolved.remove(freq)
    }

    private fun write(list: List<FmPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("f", it.freq).put("t", it.title).put("u", it.url ?: "").put("i", it.image ?: "")) }
        sp.edit().putString("presets_$city", arr.toString()).apply()
        changes.value++
    }

    /** Yayın adresi biliniyorsa çalınabilir kayıt; hazır frekans henüz aranmadıysa null. */
    fun cached(p: FmPreset): MediaEntry? =
        if (!p.url.isNullOrBlank()) MediaEntry(Kind.RADIO, p.title, p.url, p.image, "${format(p.freq)} MHz")
        else resolved[p.freq]

    /** Çalmayan yayının yerine yedeği bulununca bu oturumda frekans da yedeğe geçer. */
    fun heal(deadUrl: String, alt: MediaEntry) {
        for ((f, e) in resolved) if (e.url == deadUrl) resolved[f] = e.copy(url = alt.url, image = e.image ?: alt.image)
    }

    /** Hazır frekansın yayınını Radio Browser'da bulur (Türkiye'deki en çok dinlenen eşleşme). */
    suspend fun resolve(p: FmPreset): MediaEntry? {
        cached(p)?.let { return it }
        val q = p.search ?: return null
        val results = runCatching { RadioApi.search(q, country = "TR", limit = 20) }.getOrNull().orEmpty()
        // "Slow Türk" / "Slow Turk", "KRAL FM" / "Kral FM" gibi yazım farkları eşleşsin
        val key = norm(q)
        val best = results.firstOrNull { norm(it.title) == key }
            ?: results.firstOrNull { norm(it.title).startsWith(key) }
            ?: results.firstOrNull()
            ?: return null
        return best.copy(title = p.title, group = "${format(p.freq)} MHz").also { resolved[p.freq] = it }
    }
}
