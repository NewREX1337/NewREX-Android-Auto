// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.newrex.auto.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Telefonda Google Maps'te (ya da başka bir harita uygulamasında) bir yer "Paylaş" → NewREX seçilince, ya da bir
 * "geo:" bağlantısı NewREX ile açılınca çalışır: konumu bulur ve araç ekranındaki haritada hedef olarak işaretler.
 * Kısa Google bağlantısı (maps.app.goo.gl) gelirse koordinat için sadece o bağlantının yönlendirmesi izlenir;
 * başka hiçbir yere bir şey gönderilmez.
 */
class ShareLocationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n")
            Intent.ACTION_VIEW -> intent.dataString.orEmpty()
            else -> ""
        }
        scope.launch {
            val dest = withContext(Dispatchers.IO) { runCatching { Destination.parse(text) }.getOrNull() }
            if (dest == null) {
                Toast.makeText(applicationContext, t("Bu paylaşımda konum bulunamadı."), Toast.LENGTH_LONG).show()
            } else {
                Prefs.setDestination(dest.lat, dest.lng, dest.name)
                Prefs.dashCenter = 1
                Toast.makeText(applicationContext, t("Konum araç ekranındaki haritaya gönderildi: {0}", dest.name.ifBlank { t("Hedef") }),
                    Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

/** Paylaşılan metinden/bağlantıdan koordinat ve yer adı çıkarır. */
object Destination {
    class Dest(val lat: Double, val lng: Double, val name: String)

    private val http by lazy {
        OkHttpClient.Builder().followRedirects(true).followSslRedirects(true)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    fun parse(text: String): Dest? {
        if (text.isBlank()) return null
        val name = guessName(text)
        coords(text)?.let { (a, b) -> return Dest(a, b, name) }
        // Kısa bağlantı: yönlendirmeyi izleyip son adresteki koordinatı oku
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        val final = runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
                r.request.url.toString() + "\n" + (r.body?.string()?.take(200_000) ?: "")
            }
        }.getOrNull() ?: return null
        coords(final)?.let { (a, b) -> return Dest(a, b, name.ifBlank { placeFromUrl(final) }) }
        return null
    }

    private fun valid(a: Double, b: Double) = a in -90.0..90.0 && b in -180.0..180.0 && !(a == 0.0 && b == 0.0)

    /** Bilinen biçimler: geo:lat,lng · q=lat,lng · ll= · @lat,lng · !3dLAT!4dLNG · center= · destination= */
    private fun coords(s: String): Pair<Double, Double>? {
        val t = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
        val num = """(-?\d{1,3}\.\d+)"""
        val patterns = listOf(
            Regex("""!3d$num!4d$num"""),
            Regex("""geo:$num,\s*$num"""),
            Regex("""[?&](?:q|ll|query|destination|daddr|center)=$num,\s*$num"""),
            Regex("""@$num,$num"""),
            Regex("""(?:^|\s)$num,\s*$num(?:\s|$)"""),
        )
        for (p in patterns) {
            val m = p.find(t) ?: continue
            val a = m.groupValues[1].toDoubleOrNull() ?: continue
            val b = m.groupValues[2].toDoubleOrNull() ?: continue
            if (valid(a, b)) return a to b
        }
        return null
    }

    /** Paylaşılan metnin ilk satırı genelde yerin adıdır (bağlantı değilse). */
    private fun guessName(text: String): String =
        text.lines().map { it.replace(Regex("""(https?://|geo:)\S+"""), "").trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(60).orEmpty()

    private fun placeFromUrl(url: String): String =
        Regex("""/place/([^/@]+)""").find(url)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it).replace('+', ' ') }?.take(60).orEmpty()

}
