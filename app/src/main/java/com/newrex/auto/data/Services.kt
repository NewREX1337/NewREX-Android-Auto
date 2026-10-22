// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

/** Apple Podcasts dizini (hesap gerektirmez) ve programların kendi RSS akışları. */
object PodcastApi {
    data class Episode(val title: String, val url: String, val image: String?, val date: String, val duration: String)

    suspend fun top(country: String = "tr", limit: Int = 60): List<PodcastShow> = withContext(Dispatchers.IO) {
        val o = JSONObject(Net.get("https://rss.marketingtools.apple.com/api/v2/$country/podcasts/top/$limit/podcasts.json"))
        val arr = o.getJSONObject("feed").getJSONArray("results")
        val ids = (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }
        // Bazı programlar bölümlerini yalnızca Apple uygulamasında yayınlıyor; açık RSS'i olanları tek sorguda bul
        val withFeed = parseItunes(Net.get("https://itunes.apple.com/lookup?entity=podcast&id=" + ids.joinToString(",")))
            .associateBy { it.id }
        ids.mapNotNull { withFeed[it] }
    }

    suspend fun search(term: String): List<PodcastShow> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(term, "UTF-8")
        parseItunes(Net.get("https://itunes.apple.com/search?media=podcast&country=TR&limit=60&term=$q"))
    }

    /** Sıralama listeleri RSS adresi vermez; gerekince buradan bulunur. */
    suspend fun feedUrl(show: PodcastShow): String? = show.feedUrl ?: withContext(Dispatchers.IO) {
        parseItunes(Net.get("https://itunes.apple.com/lookup?id=${show.id}&entity=podcast")).firstOrNull()?.feedUrl
    }

    private fun parseItunes(json: String): List<PodcastShow> {
        val arr = JSONObject(json).getJSONArray("results")
        return (0 until arr.length()).mapNotNull {
            val r = arr.getJSONObject(it)
            val feed = r.optString("feedUrl").ifBlank { return@mapNotNull null }
            PodcastShow(r.optString("collectionId"), r.optString("collectionName"), r.optString("artistName"),
                r.optString("artworkUrl600").ifBlank { r.optString("artworkUrl100") }.ifBlank { null }, feed)
        }
    }

    suspend fun episodes(feedUrl: String, limit: Int = 100): List<Episode> = withContext(Dispatchers.IO) {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(StringReader(Net.get(feedUrl)))
        val out = ArrayList<Episode>()
        var showImage: String? = null
        var inItem = false
        var title = ""; var url = ""; var image: String? = null; var date = ""; var duration = ""
        while (p.next() != XmlPullParser.END_DOCUMENT && out.size < limit) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "item" -> { inItem = true; title = ""; url = ""; image = null; date = ""; duration = "" }
                    "enclosure" -> if (inItem) url = p.getAttributeValue(null, "url") ?: ""
                    "itunes:image" -> (p.getAttributeValue(null, "href"))?.let { if (inItem) image = it else showImage = showImage ?: it }
                    "title" -> if (inItem) title = p.nextText().trim()
                    "pubDate" -> if (inItem) date = formatDate(p.nextText().trim())
                    "itunes:duration" -> if (inItem) duration = formatDuration(p.nextText().trim())
                }
                XmlPullParser.END_TAG -> if (p.name == "item") {
                    inItem = false
                    if (url.isNotBlank()) out += Episode(title.ifBlank { "Bölüm" }, url, image ?: showImage, date, duration)
                }
            }
        }
        out
    }

    private fun formatDate(rfc: String): String = runCatching {
        val d = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).parse(rfc)!!
        SimpleDateFormat("d MMMM yyyy", Locale("tr", "TR")).format(d)
    }.getOrDefault("")

    private fun formatDuration(raw: String): String {
        val secs = when {
            raw.contains(':') -> raw.split(':').fold(0) { acc, part -> acc * 60 + (part.toIntOrNull() ?: 0) }
            else -> raw.toIntOrNull() ?: return ""
        }
        val h = secs / 3600; val m = (secs % 3600) / 60
        return if (h > 0) "$h sa $m dk" else "$m dk"
    }
}

/** Open-Meteo: ücretsiz, hesap ve anahtar gerektirmeyen hava durumu servisi. */
object WeatherApi {
    data class Hour(val label: String, val temp: Int, val rain: Int, val code: Int, val isDay: Boolean)
    data class Day(val label: String, val code: Int, val max: Int, val min: Int, val rain: Int)
    data class Report(
        val temp: Int, val feels: Int, val code: Int, val isDay: Boolean, val wind: Int, val humidity: Int,
        val hours: List<Hour>, val days: List<Day>, val place: String?, val time: Long = System.currentTimeMillis(),
    ) {
        /** Önümüzdeki 3 saatteki en yüksek yağış ihtimali. */
        val rainSoon: Int get() = hours.take(3).maxOfOrNull { it.rain } ?: 0
    }

    suspend fun fetch(lat: Double, lon: Double, place: String?): Report = withContext(Dispatchers.IO) {
        // Konum ~1 km hassasiyete yuvarlanır; tam yerin gönderilmesine gerek yok
        val la = "%.2f".format(Locale.US, lat)
        val lo = "%.2f".format(Locale.US, lon)
        val o = JSONObject(Net.get(
            "https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m,is_day" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=5"))
        val c = o.getJSONObject("current")
        val h = o.getJSONObject("hourly")
        val times = h.getJSONArray("time")
        val nowHour = c.getString("time").take(13)
        var start = 0
        while (start < times.length() && times.getString(start).take(13) < nowHour) start++
        val hours = (start until minOf(start + 12, times.length())).map {
            Hour(times.getString(it).substring(11, 16), h.getJSONArray("temperature_2m").optDouble(it).toInt(),
                h.getJSONArray("precipitation_probability").optInt(it), h.getJSONArray("weather_code").optInt(it),
                h.getJSONArray("is_day").optInt(it, 1) == 1)
        }
        val d = o.getJSONObject("daily")
        val dayNames = arrayOf("Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz")
        val days = (0 until d.getJSONArray("time").length()).map {
            val date = java.time.LocalDate.parse(d.getJSONArray("time").getString(it))
            Day(if (it == 0) "Bugün" else dayNames[date.dayOfWeek.value - 1],
                d.getJSONArray("weather_code").optInt(it),
                d.getJSONArray("temperature_2m_max").optDouble(it).toInt(),
                d.getJSONArray("temperature_2m_min").optDouble(it).toInt(),
                d.getJSONArray("precipitation_probability_max").optInt(it))
        }
        Report(Math.round(c.optDouble("temperature_2m")).toInt(), Math.round(c.optDouble("apparent_temperature")).toInt(),
            c.optInt("weather_code"), c.optInt("is_day", 1) == 1, Math.round(c.optDouble("wind_speed_10m")).toInt(),
            c.optInt("relative_humidity_2m"), hours, days, place)
    }

    fun emoji(code: Int, day: Boolean = true): String = when (code) {
        0 -> if (day) "☀️" else "🌙"
        1 -> if (day) "🌤️" else "🌙"
        2 -> "⛅"
        3 -> "☁️"
        45, 48 -> "🌫️"
        in 51..57 -> "🌦️"
        in 61..67, in 80..82 -> "🌧️"
        in 71..77, 85, 86 -> "🌨️"
        in 95..99 -> "⛈️"
        else -> "🌡️"
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Açık"
        1 -> "Az bulutlu"
        2 -> "Parçalı bulutlu"
        3 -> "Kapalı"
        45, 48 -> "Sisli"
        in 51..57 -> "Çiseleme"
        in 61..65 -> "Yağmurlu"
        66, 67 -> "Dondurucu yağmur"
        in 71..77 -> "Karlı"
        in 80..82 -> "Sağanak yağış"
        85, 86 -> "Kar sağanağı"
        in 95..99 -> "Gök gürültülü fırtına"
        else -> "—"
    }
}
