// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Haritada adres/yer arama. Kullanıcının TomTom anahtarı varsa TomTom'dan, yoksa ücretsiz OpenStreetMap
 * aramasından (Photon) sonuç gelir. Sadece yazılan arama ve yakındaki sonuçlar öne çıksın diye yaklaşık konum gönderilir.
 */
object PlaceSearch {
    class Place(val name: String, val address: String, val lat: Double, val lng: Double)

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** null: internete ulaşılamadı. */
    fun search(query: String, nearLat: Double?, nearLng: Double?): List<Place>? {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return (if (TomTom.has) tomtom(q, nearLat, nearLng) else null) ?: photon(q, nearLat, nearLng)
    }

    private fun lang() = if (com.newrex.auto.I18n.locale().language == "tr") "tr-TR" else "en-US"

    private fun tomtom(q: String, lat: Double?, lng: Double?): List<Place>? = runCatching {
        val enc = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val near = if (lat != null && lng != null) String.format(Locale.US, "&lat=%.4f&lon=%.4f", lat, lng) else ""
        val url = (if (Prefs.tomtomOrbis) "https://api.tomtom.com/maps/orbis/places/search/$enc.json?apiVersion=1"
            else "https://api.tomtom.com/search/2/search/$enc.json?") +
            "&limit=10&typeahead=false&language=${lang()}$near&key=${TomTom.key}"
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val arr = JSONObject(r.body!!.string()).getJSONArray("results")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val pos = o.getJSONObject("position")
                val addr = o.optJSONObject("address")?.optString("freeformAddress").orEmpty()
                val name = o.optJSONObject("poi")?.optString("name")?.takeIf { it.isNotBlank() } ?: addr
                Place(name, if (name == addr) "" else addr, pos.getDouble("lat"), pos.getDouble("lon"))
            }
        }
    }.getOrNull()

    private fun photon(q: String, lat: Double?, lng: Double?): List<Place>? = runCatching {
        val near = if (lat != null && lng != null) String.format(Locale.US, "&lat=%.4f&lon=%.4f", lat, lng) else ""
        val url = "https://photon.komoot.io/api/?q=${URLEncoder.encode(q, "UTF-8")}&limit=10$near"
        http.newCall(Request.Builder().url(url).header("User-Agent", "NewREX-Android-Auto").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val arr = JSONObject(r.body!!.string()).getJSONArray("features")
            (0 until arr.length()).map { i ->
                val f = arr.getJSONObject(i)
                val p = f.getJSONObject("properties")
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val street = listOf(p.optString("street"), p.optString("housenumber")).filter { it.isNotBlank() }.joinToString(" ")
                val addr = listOf(street, p.optString("district"), p.optString("city"), p.optString("state"))
                    .filter { it.isNotBlank() }.distinct().joinToString(", ")
                Place(p.optString("name").ifBlank { street.ifBlank { addr } }, addr, c.getDouble(1), c.getDouble(0))
            }
        }
    }.getOrNull()
}
