// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * Radyo logoları. Radio Browser'daki logoların çoğu ya yok ya da bozuk; bu durumda aynı adlı başka kayıttan
 * ya da istasyonun kendi sitesinden (apple-touch-icon, og:image, favicon) bulunur. Sonuç telefonda saklanır,
 * her açılışta tekrar aranmaz. Bulunamazsa kutucukta istasyonun baş harfleri gösterilir.
 */
object Logos {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val FOUND_DAYS = 14L
    private const val MISSING_DAYS = 3L
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val MIN_PX = 48

    /** Radio Browser'da sitesi kayıtlı olmayan bilinen istasyonlar. */
    private val SITES = mapOf(
        "joy fm" to "https://www.joyfm.com.tr/",
        "super fm" to "https://www.superfm.com.tr/",
        "metro fm" to "https://www.metrofm.com.tr/",
        "virgin radio" to "https://www.virginradioturkiye.com/",
        "radyo d" to "https://www.radyod.com/",
        "power turk" to "https://www.powerapp.com.tr/",
        "power fm" to "https://www.powerapp.com.tr/",
        "baba radyo" to "https://www.babaradyo.com.tr/",
        "a spor" to "https://www.aspor.com.tr/",
        "kral" to "https://www.kralmuzik.com.tr/",
        "radyo eksen" to "https://www.radioeksen.com/",
        "trt" to "https://www.trtdinle.com/",
    )

    /** Radyonun değil, bağlı olduğu grubun ortak logosu (ör. Virgin Radio sitesinde Karnaval logosu çıkıyor). */
    private val GENERIC = listOf("karnaval.com/media/site_media/")

    private lateinit var sp: SharedPreferences
    private val gate = Semaphore(6)

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("newrex_logos", Context.MODE_PRIVATE)
    }

    /** Daha önce bulunmuş logo; hiç aranmadıysa ya da logo yoksa null. */
    fun cached(e: MediaEntry): String? {
        if (!::sp.isInitialized || e.kind != Kind.RADIO) return null
        return sp.getString(e.url, null)?.substringAfter('|')?.takeIf { it.isNotEmpty() }
    }

    /** Kutucukta gösterilecek logo: denetlendiyse bulunan (yoksa null → baş harfler), denetlenmediyse kaydın kendi logosu. */
    fun best(e: MediaEntry): String? {
        if (!::sp.isInitialized || e.kind != Kind.RADIO) return e.image
        val v = sp.getString(e.url, null) ?: return e.image
        return v.substringAfter('|').ifEmpty { null }
    }

    private fun fresh(e: MediaEntry): Boolean {
        val v = sp.getString(e.url, null) ?: return false
        val time = v.substringBefore('|').toLongOrNull() ?: return false
        val days = if (v.substringAfter('|').isEmpty()) MISSING_DAYS else FOUND_DAYS
        return System.currentTimeMillis() - time < days * DAY
    }

    /** Listedeki istasyonların logolarını denetler; logosu değişen her istasyon için [onFound] çağrılır (null: logo yok). */
    suspend fun check(list: List<MediaEntry>, onFound: (MediaEntry, String?) -> Unit) {
        if (!::sp.isInitialized || com.newrex.auto.Saver.on) return
        val siblings = list.filter { !it.image.isNullOrBlank() }.groupBy { it.title.normName() }
        coroutineScope {
            for (e in list) {
                if (e.kind != Kind.RADIO || fresh(e)) continue
                launch {
                    val logo = gate.withPermit { find(e, siblings) }
                    sp.edit().putString(e.url, "${System.currentTimeMillis()}|${logo.orEmpty()}").apply()
                    if (logo != e.image) onFound(e, logo)
                }
            }
        }
    }

    private suspend fun find(e: MediaEntry, siblings: Map<String, List<MediaEntry>>): String? = withContext(Dispatchers.IO) {
        e.image?.let { if (isImage(it)) return@withContext it }
        siblings[e.title.normName()]?.mapNotNull { it.image }?.filter { it != e.image }?.firstOrNull { isImage(it) }
            ?.let { return@withContext it }
        val name = e.title.normName()
        val site = e.home?.takeIf { looksLikeSite(it) }
            ?: SITES.entries.firstOrNull { name.startsWith(it.key) }?.value
            ?: return@withContext null
        fromSite(site)
    }

    /** Akış adresleri (".m3u8", "streamtheworld" vb.) site sayılmaz. */
    private fun looksLikeSite(url: String) = !Regex("m3u8|\\.mp3|\\.aac|stream|:\\d{4}", RegexOption.IGNORE_CASE).containsMatchIn(url)

    private fun fromSite(site: String): String? {
        val req = Request.Builder().url(site).header("User-Agent", UA).build()
        val (base, html) = runCatching {
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                if (r.body?.contentType()?.subtype?.contains("html") != true) return null
                r.request.url to (r.body?.source()?.let { s -> s.request(400_000); s.buffer.clone().readUtf8() } ?: return null)
            }
        }.getOrNull() ?: return null
        // Öncelik: apple-touch-icon (büyük, kare) → büyük favicon → og:image → küçük favicon → bilinen yollar
        val found = ArrayList<Pair<Int, String>>()
        for (tag in Regex("<(?:link|meta)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val a = Regex("([\\w:-]+)\\s*=\\s*[\"']([^\"']*)[\"']").findAll(tag.value)
                .associate { it.groupValues[1].lowercase() to it.groupValues[2].replace("&amp;", "&") }
            val rel = a["rel"].orEmpty().lowercase()
            val prop = (a["property"] ?: a["name"]).orEmpty().lowercase()
            when {
                "apple-touch-icon" in rel -> a["href"]?.let { found += 0 to it }
                "icon" in rel -> a["href"]?.let {
                    val size = Regex("(\\d+)x").find(a["sizes"].orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    found += (if (size >= 96) 1 else 3) to it
                }
                prop == "og:image" || prop == "twitter:image" -> a["content"]?.let { found += 2 to it }
            }
        }
        found += 4 to "/apple-touch-icon.png"
        found += 5 to "/favicon.ico"
        return found.sortedBy { it.first }.asSequence()
            .map { it.second }
            .filterNot { it.substringBefore('?').endsWith(".svg", ignoreCase = true) }
            .mapNotNull { base.resolve(it)?.toString() }
            .filterNot { u -> GENERIC.any { it in u } }
            .distinct()
            .firstOrNull { isImage(it) }
    }

    /** Adres gerçekten Android'in açabildiği bir resim mi (PNG, JPEG, GIF, WebP, ICO). */
    private fun isImage(url: String): Boolean = runCatching {
        if (url.toHttpUrlOrNull() == null) return false
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        Net.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return false
            val src = r.body?.source() ?: return false
            if (!src.request(100)) return false
            val b = src.buffer.clone().readByteArray(26)
            fun at(i: Int) = b[i].toInt() and 0xFF
            // 48 pikselden küçük logolar kutucukta bulanık bir lekeye dönüşüyor; onların yerine baş harfler daha iyi
            when {
                // PNG: genişlik; gri tonlu olanlar (koyu arka planda kaybolan tek renk simgeler) alınmaz
                at(0) == 0x89 && at(1) == 0x50 -> (at(18) shl 8 or at(19)) >= MIN_PX && at(25) != 0 && at(25) != 4
                at(0) == 0 && at(1) == 0 && at(2) == 1 && at(3) == 0 -> at(6) == 0 || at(6) >= MIN_PX // ICO (0 = 256)
                at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> (at(7) shl 8 or at(6)) >= MIN_PX // GIF
                at(0) == 0xFF && at(1) == 0xD8 -> true                                           // JPEG
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 -> true                         // WEBP
                else -> false
            }
        }
    }.getOrDefault(false)
}
