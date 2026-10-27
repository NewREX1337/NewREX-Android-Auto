// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.newrex.auto.data.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Yeni sürüm var mı diye GitHub'daki son yayına bakar (en fazla birkaç saatte bir). Sadece herkese açık
 * yayın bilgisi okunur; telefondan hiçbir bilgi gönderilmez.
 */
object Updates {
    private const val API = "https://api.github.com/repos/NewREX1337/NewREX-Android-Auto/releases/latest"
    private const val PAGE = "https://github.com/NewREX1337/NewREX-Android-Auto/releases/latest"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Release(val version: String, val notes: String, val url: String)

    /** Bu açılışta zaten gösterildiyse tekrar sorma. */
    private var shown = false

    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        val sp = context.getSharedPreferences("newrex_update", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - sp.getLong("checked", 0) > CHECK_EVERY_MS) {
            runCatching {
                val j = JSONObject(Net.get(API))
                sp.edit()
                    .putLong("checked", System.currentTimeMillis())
                    .putString("version", j.optString("tag_name").removePrefix("v"))
                    .putString("notes", j.optString("body"))
                    .putString("url", j.optString("html_url").ifBlank { PAGE })
                    .apply()
            }
        }
        val version = sp.getString("version", null) ?: return@withContext null
        if (!newer(version, BuildConfig.VERSION_NAME)) return@withContext null
        Release(version, sp.getString("notes", "").orEmpty(), sp.getString("url", PAGE) ?: PAGE)
    }

    fun show(activity: Activity, r: Release) {
        if (shown || activity.isFinishing) return
        shown = true
        val notes = r.notes.lines().map { it.trim().removePrefix("-").removePrefix("*").trim() }
            .filter { it.isNotEmpty() }.joinToString("\n") { "• $it" }
        AlertDialog.Builder(activity)
            .setTitle("Yeni sürüm: ${r.version}")
            .setMessage((if (notes.isNotEmpty()) "Yenilikler:\n$notes\n\n" else "") +
                "Şu an kullandığın: ${BuildConfig.VERSION_NAME}")
            .setPositiveButton("İndir") { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
            }
            .setNegativeButton("Sonra", null)
            .show()
    }

    /** "1.10.0" > "1.9.2" gibi sayı sayı karşılaştırır. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
