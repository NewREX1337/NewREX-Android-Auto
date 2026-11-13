// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Xtream Codes API istemcisi. Kullanıcının IPTV sağlayıcısına bağlanır.
 * Veri toplamaz; tüm kimlik bilgileri yalnızca kullanıcının telefonunda saklanır.
 */
object XtreamApi {

    data class Category(val id: String, val name: String)
    data class Channel(val id: String, val name: String, val icon: String?, val categoryId: String, val url: String)
    data class VodItem(val id: String, val name: String, val icon: String?, val categoryId: String, val url: String)
    data class SeriesItem(val id: String, val name: String, val icon: String?, val categoryId: String)
    data class Episode(val id: String, val title: String, val url: String)

    // Şifrede &, #, + gibi karakterler varsa adres bozulmasın
    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun seg(s: String) = android.net.Uri.encode(s)

    private fun baseUrl(server: XtreamServer): String {
        val url = server.url.trimEnd('/')
        return "$url/player_api.php?username=${q(server.user)}&password=${q(server.pass)}"
    }

    private fun streamUrl(server: XtreamServer, id: String, ext: String = "m3u8"): String {
        val url = server.url.trimEnd('/')
        return "$url/live/${seg(server.user)}/${seg(server.pass)}/$id.$ext"
    }

    // Film ve dizi bölümleri m3u8 değil, sunucunun bildirdiği dosya türüyle (mp4, mkv…) açılır
    private fun vodUrl(server: XtreamServer, id: String, ext: String): String {
        val url = server.url.trimEnd('/')
        return "$url/movie/${seg(server.user)}/${seg(server.pass)}/$id.$ext"
    }

    private fun seriesUrl(server: XtreamServer, id: String, ext: String): String {
        val url = server.url.trimEnd('/')
        return "$url/series/${seg(server.user)}/${seg(server.pass)}/$id.$ext"
    }

    private fun ext(o: JSONObject) = o.optString("container_extension").ifBlank { "mp4" }

    /** Xtream EPG başlıkları base64 gelir. */
    private fun b64(s: String): String = runCatching {
        String(android.util.Base64.decode(s, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()?.takeIf { d -> d.isNotBlank() && d.none { it == '�' || (it < ' ' && it != '\n') } } ?: s

    suspend fun authenticate(server: XtreamServer): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = Net.get(baseUrl(server))
            val obj = JSONObject(json)
            val info = obj.optJSONObject("user_info") ?: return@withContext false
            info.optString("auth").toIntOrNull() == 1
        } catch (_: Exception) { false }
    }

    suspend fun liveCategories(server: XtreamServer): List<Category> = withContext(Dispatchers.IO) {
        parseCategories(Net.get("${baseUrl(server)}&action=get_live_categories"))
    }

    suspend fun liveStreams(server: XtreamServer, categoryId: String? = null): List<Channel> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(server)}&action=get_live_streams" + (if (categoryId != null) "&category_id=$categoryId" else "")
        val arr = JSONArray(Net.get(url))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Channel(
                id = o.optString("stream_id"),
                name = o.optString("name"),
                icon = o.optString("stream_icon").ifBlank { null },
                categoryId = o.optString("category_id"),
                url = streamUrl(server, o.optString("stream_id")),
            )
        }
    }

    suspend fun vodCategories(server: XtreamServer): List<Category> = withContext(Dispatchers.IO) {
        parseCategories(Net.get("${baseUrl(server)}&action=get_vod_categories"))
    }

    suspend fun vodStreams(server: XtreamServer, categoryId: String? = null): List<VodItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(server)}&action=get_vod_streams" + (if (categoryId != null) "&category_id=$categoryId" else "")
        val arr = JSONArray(Net.get(url))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VodItem(
                id = o.optString("stream_id"),
                name = o.optString("name"),
                icon = o.optString("stream_icon").ifBlank { null },
                categoryId = o.optString("category_id"),
                url = vodUrl(server, o.optString("stream_id"), ext(o)),
            )
        }
    }

    suspend fun seriesCategories(server: XtreamServer): List<Category> = withContext(Dispatchers.IO) {
        parseCategories(Net.get("${baseUrl(server)}&action=get_series_categories"))
    }

    suspend fun seriesList(server: XtreamServer, categoryId: String? = null): List<SeriesItem> = withContext(Dispatchers.IO) {
        val url = "${baseUrl(server)}&action=get_series" + (if (categoryId != null) "&category_id=$categoryId" else "")
        val arr = JSONArray(Net.get(url))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SeriesItem(
                id = o.optString("series_id"),
                name = o.optString("name"),
                icon = o.optString("cover").ifBlank { null },
                categoryId = o.optString("category_id"),
            )
        }
    }

    suspend fun seriesEpisodes(server: XtreamServer, seriesId: String): List<Episode> = withContext(Dispatchers.IO) {
        val json = Net.get("${baseUrl(server)}&action=get_series_info&series_id=$seriesId")
        val obj = JSONObject(json)
        val episodes = obj.optJSONObject("episodes") ?: return@withContext emptyList()
        val result = mutableListOf<Episode>()
        episodes.keys().forEach { season ->
            val arr = episodes.optJSONArray(season) ?: return@forEach
            for (i in 0 until arr.length()) {
                val ep = arr.getJSONObject(i)
                result += Episode(
                    id = ep.optString("id"),
                    title = "S${season}E${ep.optString("episode_num")} — ${ep.optString("title")}",
                    url = seriesUrl(server, ep.optString("id"), ext(ep)),
                )
            }
        }
        result
    }

    /** EPG bilgisi: şu an oynayan ve sonraki program. */
    suspend fun epg(server: XtreamServer, streamId: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val json = Net.get("${baseUrl(server)}&action=get_short_epg&stream_id=$streamId")
            val obj = JSONObject(json)
            val list = obj.optJSONArray("epg_listings") ?: return@withContext null to null
            val now = if (list.length() > 0) b64(list.getJSONObject(0).optString("title")) else null
            val next = if (list.length() > 1) b64(list.getJSONObject(1).optString("title")) else null
            now to next
        } catch (_: Exception) { null to null }
    }

    private fun parseCategories(json: String): List<Category> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Category(o.optString("category_id"), o.optString("category_name"))
        }
    }
}
