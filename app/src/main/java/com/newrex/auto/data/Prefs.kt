// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Telefon uygulaması ile araç ekranının ortak kullandığı ayarlar ve listeler. */
object Prefs {
    const val DEFAULT_PLAYLIST_NAME = "Türkiye (iptv-org)"
    const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/tr.m3u"
    private const val MAX_RECENTS = 30

    private lateinit var sp: SharedPreferences
    private lateinit var positions: SharedPreferences

    /** Favoriler veya son çalınanlar değişince artar; ekranlar bunu dinleyip yenilenir. */
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("newrex", Context.MODE_PRIVATE)
            positions = context.applicationContext.getSharedPreferences("newrex_positions", Context.MODE_PRIVATE)
            // Yeni görünüme bir kez geçilir (kırmızı tema, koyu zemin); sonra kullanıcı istediği gibi değiştirir
            if (!sp.contains("look2")) sp.edit().putBoolean("look2", true).putInt("accent", 2).putInt("bgColor", 0).apply()
        }
        Logos.init(context)
        com.newrex.auto.Saver.init(context)
    }

    /** Tema değişince artar; araç ekranı bunu dinleyip kendini yeni renklerle yeniden kurar. */
    val theme = MutableStateFlow(0)

    /** Hazır renklerden biri (0..5) ya da -1: [customAccent]. */
    var accentIndex: Int
        get() = sp.getInt("accent", 0)
        set(v) { sp.edit().putInt("accent", v).apply(); theme.value++ }

    /** Kullanıcının kendi seçtiği tema rengi. */
    var customAccent: Int
        get() = sp.getInt("customAccent", 0xFF7C5CFF.toInt())
        set(v) { sp.edit().putInt("customAccent", v).putInt("accent", -1).apply(); theme.value++ }

    /** Arka plan rengi; 0 = otomatik (tema renginin koyu tonu). */
    var bgColor: Int
        get() = sp.getInt("bgColor", 0)
        set(v) { sp.edit().putInt("bgColor", v).apply(); theme.value++ }

    /** Saat ve FM göstergesi görünümü değişince artar (araç ekranı yeniden kurulmadan güncellenir). */
    val looks = MutableStateFlow(0)

    /** Karşılama ekranındaki saatin tasarımı (ClockView.STYLES sırası). */
    var clockStyle: Int
        get() = sp.getInt("clockStyle", 0)
        set(v) { sp.edit().putInt("clockStyle", v).apply(); looks.value++ }

    /** FM sayfasındaki frekans göstergesinin tasarımı (FmDisplayView.STYLES sırası). */
    var fmStyle: Int
        get() = sp.getInt("fmStyle", 0)
        set(v) { sp.edit().putInt("fmStyle", v).apply(); looks.value++ }

    /** TV görüntü kalitesi (PlayerHub.QUALITIES sırası); 0 = Otomatik (en iyisi). */
    var tvQuality: Int
        get() = sp.getInt("tvQuality", 0)
        set(v) { sp.edit().putInt("tvQuality", v).apply(); bump(); com.newrex.auto.PlayerHub.applyQuality() }

    /** Tasarruf modu: 0 kapalı, 1 açık, 2 otomatik (pil tasarrufu / ısınma). Varsayılan otomatik. */
    var saverMode: Int
        get() = sp.getInt("saverMode", 2)
        set(v) { sp.edit().putInt("saverMode", v).apply(); bump(); com.newrex.auto.Saver.update() }

    /** true → sürüşte video/web gizlenir (varsayılan); false → görüntü açık kalır. */
    var parkModeVideo: Boolean
        get() = sp.getBoolean("parkModeVideo", true)
        set(v) { sp.edit().putBoolean("parkModeVideo", v).apply(); bump() }

    /** Tam ekran videonun ekrana yerleşimi: 0 sığdır (kenarda siyah bant), 1 doldur (kenarlar kırpılır), 2 uzat. */
    var videoFit: Int
        get() = sp.getInt("videoFit", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("videoFit", v).apply()

    var playlists: List<Playlist>
        get() {
            val raw = sp.getString("playlists", null)
                ?: return listOf(Playlist(DEFAULT_PLAYLIST_NAME, DEFAULT_PLAYLIST_URL))
            val arr = JSONArray(raw)
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Playlist(o.optString("name"), o.optString("url"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("playlists", arr.toString()).apply()
            bump()
        }

    var lastCarBind: Long
        get() = sp.getLong("diag_bind", 0)
        set(v) = sp.edit().putLong("diag_bind", v).apply()

    /** Arabaya giriş kapısı: 0 otomatik, 1 normal, 2 park (bkz. CarEntry). */
    var carEntry: Int
        get() = sp.getInt("carEntry", 0)
        set(v) = sp.edit().putInt("carEntry", v).apply()

    // ---- Sesli karşılama ----

    var greetingEnabled: Boolean
        get() = sp.getBoolean("greetEnabled", false)
        set(v) { sp.edit().putBoolean("greetEnabled", v).apply(); bump() }

    var greetingName: String
        get() = sp.getString("greetName", "") ?: ""
        set(v) { sp.edit().putString("greetName", v).apply(); bump() }

    var greetingOncePerDay: Boolean
        get() = sp.getBoolean("greetOnce", true)
        set(v) { sp.edit().putBoolean("greetOnce", v).apply(); bump() }

    var lastGreetingDay: Long
        get() = sp.getLong("greetDay", 0)
        set(v) = sp.edit().putLong("greetDay", v).apply()

    // ---- Web kısayolları ----

    var webShortcuts: List<WebShortcut>
        get() {
            val arr = JSONArray(sp.getString("webShortcuts", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                WebShortcut(o.optString("name"), o.optString("url"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.take(6).forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url)) }
            sp.edit().putString("webShortcuts", arr.toString()).apply()
            bump()
        }

    // ---- Kart sırası ----

    var cardOrder: List<String>
        get() {
            val raw = sp.getString("cardOrder", null) ?: return emptyList()
            val arr = JSONArray(raw)
            return (0 until arr.length()).map { arr.getString(it) }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            sp.edit().putString("cardOrder", arr.toString()).apply()
            bump()
        }

    // ---- Xtream Codes ----

    var xtreamServers: List<XtreamServer>
        get() {
            val arr = JSONArray(sp.getString("xtream", "[]"))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                XtreamServer(o.optString("name"), o.optString("url"), o.optString("user"), o.optString("pass"))
            }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("user", it.user).put("pass", it.pass)) }
            sp.edit().putString("xtream", arr.toString()).apply()
            bump()
        }

    // ---- Ekolayzır ----

    /** Hıza göre ses: 0 kapalı, 1 az, 2 orta, 3 çok. */
    var speedVolume: Int
        get() = sp.getInt("speedVol", 1)
        set(v) { sp.edit().putInt("speedVol", v).apply(); bump() }

    /** Kullanıcının kendi TomTom anahtarı (isteğe bağlı; trafik ve trafikli rota için). Sadece telefonda saklanır. */
    var tomtomKey: String
        get() = sp.getString("tomtomKey", "") ?: ""
        set(v) { sp.edit().putString("tomtomKey", v).apply(); bump() }

    /** Anahtarın çalıştığı TomTom sistemi: 1 yeni (Orbis), 0 eski. */
    var tomtomOrbis: Boolean
        get() = sp.getBoolean("tomtomOrbis", true)
        set(v) = sp.edit().putBoolean("tomtomOrbis", v).apply()

    /** Seçilen OBD-II (ELM327) Bluetooth adaptörü. */
    var obdMac: String
        get() = sp.getString("obdMac", "") ?: ""
        set(v) { sp.edit().putString("obdMac", v).apply(); bump() }
    var obdName: String
        get() = sp.getString("obdName", "") ?: ""
        set(v) { sp.edit().putString("obdName", v).apply(); bump() }

    var eqEnabled: Boolean
        get() = sp.getBoolean("eqOn", false)
        set(v) { sp.edit().putBoolean("eqOn", v).apply(); bump() }

    var eqBands: IntArray
        get() {
            val raw = sp.getString("eqBands", null) ?: return intArrayOf(0, 0, 0, 0, 0)
            return raw.split(",").map { it.toIntOrNull() ?: 0 }.toIntArray()
        }
        set(v) { sp.edit().putString("eqBands", v.joinToString(",")).apply() }

    /**
     * "Hey <kelime>" ile sesli uyandırma açık mı. Varsayılan kapalı: sürekli dinlemek telefonun ses tanımasını
     * hep çalıştırıyor ve bir işlemci çekirdeğinin ~%25'ini kullanıyor (ölçüldü).
     */
    var wakeEnabled: Boolean
        get() = sp.getBoolean("wakeOn2", false)
        set(v) = sp.edit().putBoolean("wakeOn2", v).apply()

    /** Telefondan paylaşılan hedef (Google Maps → Paylaş → NewREX); değişince artar. */
    val destination = MutableStateFlow(0)

    fun setDestination(lat: Double, lng: Double, name: String) {
        sp.edit().putLong("destLat", java.lang.Double.doubleToRawLongBits(lat)).putLong("destLng", java.lang.Double.doubleToRawLongBits(lng))
            .putString("destName", name).apply()
        destination.value++
    }

    fun clearDestination() {
        sp.edit().remove("destLat").remove("destLng").remove("destName").apply()
        destination.value++
    }

    /** (enlem, boylam, ad) ya da yoksa null. */
    fun destination(): Triple<Double, Double, String>? {
        if (!sp.contains("destLat")) return null
        return Triple(java.lang.Double.longBitsToDouble(sp.getLong("destLat", 0)), java.lang.Double.longBitsToDouble(sp.getLong("destLng", 0)),
            sp.getString("destName", "") ?: "")
    }

    /** Araç sayfası göstergesinin ortası: 0 dönen araç, 1 harita. */
    var dashCenter: Int
        get() = sp.getInt("dashCenter", 0)
        set(v) { sp.edit().putInt("dashCenter", v).apply(); destination.value++ }

    /** Araç sayfasındaki 360° aracın paketi ve rengi (Toyota C-HR). */
    var spinGrade: String
        get() = sp.getString("spinGrade2", "xsport") ?: "xsport"
        set(v) = sp.edit().putString("spinGrade2", v).apply()
    var spinPaint: String
        get() = sp.getString("spinPaint2", "2MR") ?: "2MR"
        set(v) = sp.edit().putString("spinPaint2", v).apply()

    /** Hazır arka plan stili (0: hareketli dalga). [com.newrex.auto.ui.Backdrop] */
    var bgStyle: Int
        get() = sp.getInt("bgStyle", 5)
        set(v) { sp.edit().putInt("bgStyle", v).apply(); theme.value++ }

    /** "Hey"den sonra söylenecek kelime; kullanıcı seçer (ör. "newrex", "maleren"). */
    var wakeWord: String
        get() = sp.getString("wakeWord", "newrex") ?: "newrex"
        set(v) = sp.edit().putString("wakeWord", v.trim()).apply()

    /** Özel arka plan fotoğrafı değiştiğinde artar (0: yok). Dosya: files/background.jpg */
    var bgImageVersion: Long
        get() = sp.getLong("bgImage", 0L)
        set(v) { sp.edit().putLong("bgImage", v).apply(); theme.value++ }

    /** NewREX'teki YouTube verileri bir kez sıfırlandı mı (kullanıcı istedi, 2026-11-13). */
    var ytResetDone: Boolean
        get() = sp.getBoolean("ytReset1", false)
        set(v) = sp.edit().putBoolean("ytReset1", v).apply()

    var immersiveMode: Boolean
        get() = sp.getBoolean("immersive", false)
        set(v) { sp.edit().putBoolean("immersive", v).apply(); changes.value++ }

    /** "Arabada görünür yap" en son hangi sürümde denendi (0: hiç). Tutmadıysa aynı uyarı tekrar tekrar çıkmasın diye. */
    var selfInstallTried: Int
        get() = sp.getInt("selfInstallTried", 0)
        set(v) = sp.edit().putInt("selfInstallTried", v).apply()

    /** "Kurulum bu telefonda tutmadı" bilgisi hangi sürümde gösterildi. */
    var installFailShown: Int
        get() = sp.getInt("installFailShown", 0)
        set(v) = sp.edit().putInt("installFailShown", v).apply()

    /** Araç ekranı "park uygulaması" kapısından en son ne zaman açıldı. */
    var lastParkedSession: Long
        get() = sp.getLong("diag_parked", 0)
        set(v) = sp.edit().putLong("diag_parked", v).apply()

    var lastCarSession: Long
        get() = sp.getLong("diag_session", 0)
        set(v) = sp.edit().putLong("diag_session", v).apply()

    // ---- Podcast ----

    var podcastSubs: List<PodcastShow>
        get() {
            val arr = JSONArray(sp.getString("podcast_subs", "[]"))
            return (0 until arr.length()).map { PodcastShow.fromJson(arr.getJSONObject(it)) }
        }
        set(list) {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            sp.edit().putString("podcast_subs", arr.toString()).apply()
            bump()
        }

    fun isSubscribed(id: String) = podcastSubs.any { it.id == id }

    fun toggleSubscription(show: PodcastShow): Boolean {
        val list = podcastSubs.toMutableList()
        val i = list.indexOfFirst { it.id == show.id }
        val now = i < 0
        if (now) list.add(0, show) else list.removeAt(i)
        podcastSubs = list
        return now
    }

    /** Podcast bölümlerinde kalınan yer (ms). Bölüm bitince silinir. */
    fun position(url: String): Long = positions.getLong(url, 0L)

    fun savePosition(url: String, ms: Long) {
        val e = positions.edit()
        if (ms <= 0) e.remove(url) else e.putLong(url, ms)
        // Çok eski kayıtlar birikmesin
        if (positions.all.size > 300) positions.all.keys.take(100).forEach { e.remove(it) }
        e.apply()
    }

    // ---- Park yeri ----

    var parkedSpot: ParkedSpot?
        get() {
            if (!sp.contains("park_lat")) return null
            return ParkedSpot(
                java.lang.Double.longBitsToDouble(sp.getLong("park_lat", 0)),
                java.lang.Double.longBitsToDouble(sp.getLong("park_lon", 0)),
                sp.getLong("park_time", 0), sp.getFloat("park_acc", 0f),
            )
        }
        set(v) {
            val e = sp.edit()
            if (v == null) e.remove("park_lat").remove("park_lon").remove("park_time").remove("park_acc")
            else e.putLong("park_lat", java.lang.Double.doubleToRawLongBits(v.lat))
                .putLong("park_lon", java.lang.Double.doubleToRawLongBits(v.lon))
                .putLong("park_time", v.time).putFloat("park_acc", v.accuracy)
            e.apply()
            bump()
        }

    // ---- Bakım hatırlatıcı (tarihler epoch günü olarak) ----

    fun maintenanceDay(m: Maintenance): Long? =
        if (sp.contains("mnt_${m.name}")) sp.getLong("mnt_${m.name}", 0) else null

    fun setMaintenanceDay(m: Maintenance, epochDay: Long?) {
        val e = sp.edit()
        if (epochDay == null) e.remove("mnt_${m.name}") else e.putLong("mnt_${m.name}", epochDay)
        e.apply()
        bump()
    }

    /** Tarihi girilmiş kalemler, en yakını önce: (kalem, kalan gün). */
    fun maintenanceDue(): List<Pair<Maintenance, Long>> {
        val today = java.time.LocalDate.now().toEpochDay()
        return Maintenance.values().mapNotNull { m -> maintenanceDay(m)?.let { m to (it - today) } }
            .sortedBy { it.second }
    }

    fun favorites(kind: Kind? = null): List<MediaEntry> =
        readEntries("favorites").filter { kind == null || it.kind == kind }

    fun isFavorite(e: MediaEntry) = readEntries("favorites").any { it.key == e.key }

    /** Favoriye ekler ya da çıkarır; yeni durumu döndürür. */
    fun toggleFavorite(e: MediaEntry): Boolean {
        val list = readEntries("favorites").toMutableList()
        val existing = list.indexOfFirst { it.key == e.key }
        val nowFav = existing < 0
        if (nowFav) list.add(0, e) else list.removeAt(existing)
        writeEntries("favorites", list)
        return nowFav
    }

    fun recents(): List<MediaEntry> = readEntries("recents")

    fun addRecent(e: MediaEntry) {
        val list = readEntries("recents").filterNot { it.key == e.key }.toMutableList()
        list.add(0, e)
        writeEntries("recents", list.take(MAX_RECENTS))
    }

    // Favoriler/son çalınanlar her kutucuk ve mini oynatıcı için yeniden JSON'dan okunuyordu; kayıt değişmedikçe
    // önceki sonuç kullanılır
    private val entryCache = HashMap<String, Pair<String, List<MediaEntry>>>()

    @Synchronized
    private fun readEntries(key: String): List<MediaEntry> {
        val raw = sp.getString(key, "[]") ?: "[]"
        entryCache[key]?.let { (r, list) -> if (r === raw || r == raw) return list }
        val arr = JSONArray(raw)
        val list = (0 until arr.length()).map { MediaEntry.fromJson(arr.getJSONObject(it)) }
        entryCache[key] = raw to list
        return list
    }

    private fun writeEntries(key: String, list: List<MediaEntry>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString(key, arr.toString()).apply()
        bump()
    }

    private fun bump() {
        changes.value = changes.value + 1
    }
}
