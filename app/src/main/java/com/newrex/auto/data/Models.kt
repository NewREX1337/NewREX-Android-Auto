// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import org.json.JSONObject

enum class Kind { TV, RADIO, PODCAST }

/** Oynatılabilir her şey: TV kanalı, radyo istasyonu ya da podcast bölümü. */
data class MediaEntry(
    val kind: Kind,
    val title: String,
    val url: String,
    val image: String?,
    val group: String,
) {
    val key: String get() = "$kind|$url"

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name)
        .put("title", title)
        .put("url", url)
        .put("image", image ?: "")
        .put("group", group)

    companion object {
        fun fromJson(o: JSONObject) = MediaEntry(
            kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.TV),
            title = o.optString("title"),
            url = o.optString("url"),
            image = o.optString("image").ifBlank { null },
            group = o.optString("group"),
        )
    }
}

data class Playlist(val name: String, val url: String)

/** Abone olunan / listelenen podcast programı. */
data class PodcastShow(val id: String, val name: String, val author: String, val image: String?, val feedUrl: String?) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("author", author)
        .put("image", image ?: "").put("feed", feedUrl ?: "")

    companion object {
        fun fromJson(o: JSONObject) = PodcastShow(o.optString("id"), o.optString("name"), o.optString("author"),
            o.optString("image").ifBlank { null }, o.optString("feed").ifBlank { null })
    }
}

/** Aracın en son park edildiği yer (Android Auto bağlantısı kesildiğinde kaydedilir). */
data class ParkedSpot(val lat: Double, val lon: Double, val time: Long, val accuracy: Float)

/** Bakım hatırlatıcı kalemleri. [months] bir sonraki tarihi önermek için kullanılır. */
enum class Maintenance(val title: String, val emoji: String, val months: Int) {
    MUAYENE("Araç muayenesi", "🔍", 24),
    TRAFIK("Trafik sigortası", "📄", 12),
    KASKO("Kasko", "🛡️", 12),
    SERVIS("Periyodik bakım / yağ", "🛢️", 12),
    LASTIK("Lastik değişimi", "🛞", 6),
    MTV("MTV ödemesi", "💳", 6),
}
