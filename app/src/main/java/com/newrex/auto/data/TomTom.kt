// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * İsteğe bağlı TomTom desteği: kullanıcı kendi ücretsiz anahtarını telefonda girerse haritada canlı trafik renkleri
 * ve trafiğe göre rota/varış süresi gelir. Uygulamada hazır anahtar yoktur; anahtar sadece kullanıcının telefonunda
 * saklanır ve sadece TomTom'a gönderilir. Anahtar yoksa ya da çalışmazsa ücretsiz rota kullanılır.
 */
object TomTom {
    class Route(val points: List<DoubleArray>, val meters: Double, val seconds: Double, val delaySeconds: Double)

    const val SIGNUP_URL = "https://developer.tomtom.com/user/register"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }

    /** Sadece harf/rakam: adrese ya da harita stiline güvenle konabilsin. */
    fun clean(k: String) = k.trim().filter { it.isLetterOrDigit() }

    val key: String get() = clean(Prefs.tomtomKey)
    val has: Boolean get() = key.length >= 20

    /** Haritanın üstüne binen trafik katmanı (yeşil/sarı/kırmızı yollar). Yeni (Orbis) ya da eski sistem. */
    fun trafficTiles(k: String = key) =
        if (Prefs.tomtomOrbis) "https://api.tomtom.com/maps/orbis/traffic/flow/raster/tile/{z}/{x}/{y}?apiVersion=2&style=dark&key=$k"
        else "https://api.tomtom.com/traffic/map/4/tile/flow/relative0-dark/{z}/{x}/{y}.png?key=$k&tileSize=256&thickness=10"

    private fun routeUrl(orbis: Boolean, a: Double, b: Double, c: Double, d: Double, k: String) = String.format(Locale.US,
        if (orbis) "https://api.tomtom.com/maps/orbis/routing/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?apiVersion=2&traffic=live&routeType=fast&travelMode=car&key=%s"
        else "https://api.tomtom.com/routing/1/calculateRoute/%.6f,%.6f:%.6f,%.6f/json?traffic=true&routeType=fastest&travelMode=car&key=%s",
        a, b, c, d, k)

    /**
     * Anahtar çalışıyor mu? Önce yeni (Orbis), olmazsa eski sistemle kısa bir rota denenir; çalışan hatırlanır.
     * null: internete ulaşılamadı, true/false: sonuç.
     */
    fun test(k: String): Boolean? {
        var offline = true
        for (orbis in listOf(true, false)) {
            val code = runCatching {
                http.newCall(Request.Builder().url(routeUrl(orbis, 39.9208, 32.8541, 39.9255, 32.8369, clean(k))).build()).execute().use { it.code }
            }.getOrNull() ?: continue
            offline = false
            if (code in 200..299) { Prefs.tomtomOrbis = orbis; return true }
        }
        return if (offline) null else false
    }

    /** Trafiğe göre en hızlı rota. Hata/limit dolması durumunda null (çağıran ücretsiz rotaya düşer). */
    fun route(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): Route? {
        if (!has) return null
        val url = routeUrl(Prefs.tomtomOrbis, fromLat, fromLng, toLat, toLng, key)
        return runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val o = JSONObject(r.body!!.string()).getJSONArray("routes").getJSONObject(0)
                val s = o.getJSONObject("summary")
                val pts = ArrayList<DoubleArray>()
                val legs = o.getJSONArray("legs")
                for (l in 0 until legs.length()) {
                    val arr = legs.getJSONObject(l).getJSONArray("points")
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONObject(i)
                        pts += doubleArrayOf(p.getDouble("longitude"), p.getDouble("latitude"))
                    }
                }
                Route(pts, s.getDouble("lengthInMeters"), s.getDouble("travelTimeInSeconds"), s.optDouble("trafficDelayInSeconds", 0.0))
            }
        }.getOrNull()
    }
}
