// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Application
import android.content.Context
import java.util.Locale

/**
 * Uygulama dili. Kaynak metinler kodda Türkçe yazılıdır; [t] bunları seçili dile çevirir.
 * Çeviriler assets/i18n altında: keys.txt (numara|Türkçe) ve her dil için <kod>.txt (numara|çeviri).
 * Çevirisi olmayan metin İngilizceye, o da yoksa Türkçeye düşer. Dil değişince uygulama yeniden başlatılır.
 */
object I18n {
    class Lang(val code: String, val name: String)

    val LANGS = listOf(
        Lang("tr", "Türkçe"), Lang("en", "English"), Lang("de", "Deutsch"), Lang("fr", "Français"),
        Lang("es", "Español"), Lang("it", "Italiano"), Lang("pt", "Português"), Lang("nl", "Nederlands"),
        Lang("pl", "Polski"), Lang("ru", "Русский"), Lang("uk", "Українська"),
    )

    const val AUTO = "auto"
    private const val PREFS = "newrex"
    private const val KEY = "lang"

    @Volatile private var map: Map<String, String> = emptyMap()
    @Volatile var code: String = "tr"; private set
    private var ready = false

    /** Kullanıcının seçimi: [AUTO] ya da bir dil kodu. */
    fun choice(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    fun setChoice(c: Context, v: String) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).commit()
    }

    /** "auto" → telefonun dili destekleniyorsa o, değilse İngilizce. */
    fun resolve(choice: String): String {
        if (choice != AUTO && LANGS.any { it.code == choice }) return choice
        val sys = Locale.getDefault().language
        return if (LANGS.any { it.code == sys }) sys else "en"
    }

    fun locale(): Locale = Locale(code)

    /** Konuşma tanıma / seslendirme için dil etiketi (ör. tr-TR). */
    fun speechTag(): String = when (code) {
        "tr" -> "tr-TR"; "en" -> "en-US"; "de" -> "de-DE"; "fr" -> "fr-FR"; "es" -> "es-ES"; "it" -> "it-IT"
        "pt" -> "pt-BR"; "nl" -> "nl-NL"; "pl" -> "pl-PL"; "ru" -> "ru-RU"; "uk" -> "uk-UA"; else -> "en-US"
    }

    @Synchronized
    fun init(c: Context) {
        if (ready) return
        ready = true
        code = resolve(choice(c))
        if (code == "tr") return
        val am = c.applicationContext.assets
        fun read(name: String): Map<Int, String> = runCatching {
            am.open("i18n/$name").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.mapNotNull { line ->
                    val i = line.indexOf('|')
                    if (i <= 0) null else line.substring(0, i).toIntOrNull()?.let { it to unescape(line.substring(i + 1)) }
                }.toMap()
            }
        }.getOrDefault(emptyMap())
        val keys = read("keys.txt")
        val lang = read("$code.txt")
        val en = if (code == "en") lang else read("en.txt")
        val m = HashMap<String, String>(keys.size * 2)
        for ((id, src) in keys) (lang[id] ?: en[id])?.let { m[src] = it }
        map = m
    }

    fun tr(src: String): String = map[src] ?: src

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> b.append('\n'); 't' -> b.append('\t'); else -> b.append(n)
                }
                i += 2
            } else { b.append(ch); i++ }
        }
        return b.toString()
    }

    /** Dili değiştirip uygulamayı yeniden başlatır (sabit listelerdeki yazılar da yeni dille kurulsun). */
    fun applyAndRestart(activity: android.app.Activity, choice: String) {
        setChoice(activity, choice)
        val intent = activity.packageManager.getLaunchIntentForPackage(activity.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }
}

/** Türkçe kaynak metni seçili dile çevirir; {0}, {1}… yerine verilen değerler konur. */
fun t(src: String, vararg args: Any?): String {
    val s = I18n.tr(src)
    if (args.isEmpty()) return s
    val b = StringBuilder(s.length + 16)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '{') {
            val end = s.indexOf('}', i)
            val n = if (end > i) s.substring(i + 1, end).toIntOrNull() else null
            if (n != null && n in args.indices) { b.append(args[n]); i = end + 1; continue }
        }
        b.append(ch); i++
    }
    return b.toString()
}

class NewRexApp : Application() {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        // Telefon uygulamalarını araçta açan yardımcı bu açılışta başlamadıysa Wi-Fi'de başlatılsın
        runCatching { SelfAdb.init(this) }
    }
}
