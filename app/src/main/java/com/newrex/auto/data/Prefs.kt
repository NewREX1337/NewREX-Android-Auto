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

    /** Favoriler veya son çalınanlar değişince artar; ekranlar bunu dinleyip yenilenir. */
    val changes = MutableStateFlow(0)

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("newrex", Context.MODE_PRIVATE)
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
