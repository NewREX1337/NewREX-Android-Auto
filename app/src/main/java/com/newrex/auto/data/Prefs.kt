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
        }
    }

    var accentIndex: Int
        get() = sp.getInt("accent", 0)
        set(v) = sp.edit().putInt("accent", v).apply()

    /** true → sürüşte video/web gizlenir (varsayılan); false → görüntü açık kalır. */
    var parkModeVideo: Boolean
        get() = sp.getBoolean("parkModeVideo", true)
        set(v) { sp.edit().putBoolean("parkModeVideo", v).apply(); bump() }

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

    private fun readEntries(key: String): List<MediaEntry> {
        val arr = JSONArray(sp.getString(key, "[]"))
        return (0 until arr.length()).map { MediaEntry.fromJson(arr.getJSONObject(it)) }
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
