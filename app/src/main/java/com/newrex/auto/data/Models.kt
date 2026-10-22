// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import org.json.JSONObject

enum class Kind { TV, RADIO }

/** Oynatılabilir her şey: bir TV kanalı ya da bir radyo istasyonu. */
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
            kind = Kind.valueOf(o.optString("kind", Kind.TV.name)),
            title = o.optString("title"),
            url = o.optString("url"),
            image = o.optString("image").ifBlank { null },
            group = o.optString("group"),
        )
    }
}

data class Playlist(val name: String, val url: String)
