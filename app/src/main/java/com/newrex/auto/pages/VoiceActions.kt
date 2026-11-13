// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.I18n
import com.newrex.auto.Mirror
import com.newrex.auto.PlayerHub
import com.newrex.auto.VoiceCommand
import com.newrex.auto.data.FmPresets
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Playlists
import com.newrex.auto.data.Prefs
import com.newrex.auto.data.RadioApi
import com.newrex.auto.t
import com.newrex.auto.ui.CarUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private typealias Words = Map<String, List<String>>

/** Mikrofonla komut dinler: uygulamanın her bölümü, kanal/radyo, telefon uygulamaları, arama ve oynatma kontrolü. */
fun startVoice(ui: CarUi, greeted: Boolean = false) {
    VoiceCommand.listen(ui.ctx, onError = { ui.toast(it) }) { texts -> VoiceActions.handle(ui, texts) }
    if (VoiceCommand.listening.value && !greeted)
        ui.toast(t("Dinliyorum… Ör: \"TRT 1 aç\", \"hava durumu\", \"YouTube'da Tarkan ara\", \"Spotify'ı aç\""), long = true)
}

/**
 * Söylenen cümleyi işe çevirir. Her dil için anahtar sözcük listesi var; sözcük başı eşleşir
 * ("radyoyu", "Radios" gibi ekli halleri de tutar). Sıra: kontrol komutları → arama → bölüm aç →
 * kanal/radyo/telefon uygulaması adı. Tanıyıcının birkaç tahmini sırayla denenir.
 */
object VoiceActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ---- Sözcük listeleri (dil kodu → ifadeler; "*" her dilde geçerli) ----

    private val NEXT: Words = mapOf(
        "tr" to listOf("sonraki", "sıradaki", "bir sonraki", "diğer kanal", "ileri"), "en" to listOf("next", "skip"),
        "de" to listOf("nächste", "nächster", "weiter"), "fr" to listOf("suivant", "suivante"), "es" to listOf("siguiente"),
        "it" to listOf("successivo", "prossimo", "prossima"), "pt" to listOf("próximo", "próxima", "seguinte"),
        "nl" to listOf("volgende"), "pl" to listOf("następny", "następna", "następne", "dalej"),
        "ru" to listOf("следующ", "дальше"), "uk" to listOf("наступн", "далі"),
    )
    private val PREV: Words = mapOf(
        "tr" to listOf("önceki", "bir önceki"), "en" to listOf("previous"), "de" to listOf("vorherige", "vorheriger"),
        "fr" to listOf("précédent", "précédente"), "es" to listOf("anterior"), "it" to listOf("precedente"),
        "pt" to listOf("anterior"), "nl" to listOf("vorige"), "pl" to listOf("poprzedni", "poprzednia"),
        "ru" to listOf("предыдущ"), "uk" to listOf("попередн"),
    )
    private val PAUSE: Words = mapOf(
        "tr" to listOf("durdur", "duraklat", "sus", "kapat", "dur"), "en" to listOf("pause", "stop"),
        "de" to listOf("pause", "stopp", "stop", "anhalten"), "fr" to listOf("pause", "arrête", "stop"),
        "es" to listOf("pausa", "para", "detén", "detener"), "it" to listOf("pausa", "ferma", "stop"),
        "pt" to listOf("pausa", "pare", "parar"), "nl" to listOf("pauze", "stop", "stoppen"),
        "pl" to listOf("pauza", "zatrzymaj", "stop"), "ru" to listOf("пауза", "стоп", "останов"), "uk" to listOf("пауза", "стоп", "зупин"),
    )
    private val RESUME: Words = mapOf(
        "tr" to listOf("devam", "devam et", "oynat", "çal", "başlat"), "en" to listOf("resume", "play", "continue"),
        "de" to listOf("weiter", "fortsetzen", "abspielen"), "fr" to listOf("reprendre", "reprends", "lecture", "joue"),
        "es" to listOf("reanudar", "continúa", "continuar", "reproducir"), "it" to listOf("riprendi", "continua", "riproduci"),
        "pt" to listOf("retomar", "continuar", "continua", "tocar"), "nl" to listOf("hervat", "verder", "afspelen"),
        "pl" to listOf("wznów", "kontynuuj", "graj"), "ru" to listOf("продолж", "играй", "воспроизвед"), "uk" to listOf("продовж", "грай", "відтвор"),
    )
    private val VOLUME: Words = mapOf(
        "tr" to listOf("sesi aç", "sesi yükselt", "sesi artır", "sesi kıs", "sesi azalt", "sesi düşür", "ses seviyesi"),
        "en" to listOf("volume", "louder", "quieter"), "de" to listOf("lauter", "leiser", "lautstärke"),
        "fr" to listOf("volume", "plus fort", "moins fort"), "es" to listOf("volumen", "más alto", "más bajo"),
        "it" to listOf("volume", "alza", "abbassa"), "pt" to listOf("volume", "mais alto", "mais baixo"),
        "nl" to listOf("volume", "harder", "zachter"), "pl" to listOf("głośność", "głośniej", "ciszej"),
        "ru" to listOf("громкост", "громче", "тише"), "uk" to listOf("гучн", "тихіше"),
    )
    private val TIME: Words = mapOf(
        "tr" to listOf("saat kaç"), "en" to listOf("what time"), "de" to listOf("wie spät", "uhrzeit"),
        "fr" to listOf("quelle heure"), "es" to listOf("qué hora"), "it" to listOf("che ore", "che ora"),
        "pt" to listOf("que horas"), "nl" to listOf("hoe laat"), "pl" to listOf("która godzina", "która jest godzina"),
        "ru" to listOf("который час", "сколько времени"), "uk" to listOf("котра година", "скільки часу"),
    )
    private val HELP: Words = mapOf(
        "tr" to listOf("yardım", "neler yapabilirsin", "ne yapabilirsin", "komutlar"), "en" to listOf("help", "what can you do", "commands"),
        "de" to listOf("hilfe", "was kannst du"), "fr" to listOf("aide", "que peux-tu faire"), "es" to listOf("ayuda", "qué puedes hacer"),
        "it" to listOf("aiuto", "cosa puoi fare"), "pt" to listOf("ajuda", "o que você pode fazer"), "nl" to listOf("help", "wat kun je"),
        "pl" to listOf("pomoc", "co potrafisz"), "ru" to listOf("помощь", "что ты умеешь"), "uk" to listOf("допомога", "що ти вмієш"),
    )
    private val BACK: Words = mapOf(
        "tr" to listOf("geri dön", "geri git", "geri"), "en" to listOf("go back", "back"), "de" to listOf("zurück"),
        "fr" to listOf("retour", "reviens"), "es" to listOf("atrás", "volver"), "it" to listOf("indietro"),
        "pt" to listOf("voltar", "volta"), "nl" to listOf("terug"), "pl" to listOf("wstecz", "wróć"),
        "ru" to listOf("назад"), "uk" to listOf("назад"),
    )
    private val SEARCH: Words = mapOf(
        "tr" to listOf("ara", "arat", "bul", "araştır"), "en" to listOf("search for", "search", "look up", "find"),
        "de" to listOf("suche nach", "suche", "such", "finde"), "fr" to listOf("cherche", "recherche", "trouve"),
        "es" to listOf("busca", "buscar", "encuentra"), "it" to listOf("cerca", "trova"), "pt" to listOf("procura", "pesquisa", "busca"),
        "nl" to listOf("zoek naar", "zoek", "vind"), "pl" to listOf("szukaj", "wyszukaj", "znajdź"),
        "ru" to listOf("найди", "поиск", "ищи", "искать"), "uk" to listOf("знайди", "пошук", "шукай"),
    )

    /** Komutun başında/sonunda olup atılan fiil ve dolgu sözcükleri. */
    private val FILLER: Words = mapOf(
        "tr" to listOf("açar mısın", "açabilir misin", "açsana", "açalım", "aç", "açın", "aç bakalım", "koy", "koyar mısın", "başlat",
            "oynat", "çal", "çalar mısın", "dinle", "dinlet", "dinleyelim", "dinlemek istiyorum", "izle", "izlet", "izleyelim",
            "izlemek istiyorum", "geç", "git", "göster", "getir", "lütfen", "bana", "biraz", "şu", "şunu", "hadi", "bir",
            "kanalını", "kanalı", "kanalına", "kanal", "radyosunu", "radyosu", "radyosuna", "istasyonunu", "istasyonu",
            "uygulamasını", "uygulaması", "uygulamayı", "sayfasını", "sayfası", "ekranını", "ekranı", "programını"),
        "en" to listOf("can you", "could you", "please", "open up", "open", "start", "launch", "show me", "show", "play", "put on",
            "go to", "switch to", "turn on", "listen to", "watch", "i want to", "the", "app", "channel", "station", "page"),
        "de" to listOf("kannst du", "bitte", "öffne", "öffnen", "starte", "starten", "zeige", "zeig", "spiele", "spiel", "mach",
            "an", "geh zu", "wechsle zu", "hören", "schauen", "den", "die", "das", "sender", "app", "seite"),
        "fr" to listOf("peux-tu", "s'il te plaît", "s'il vous plaît", "ouvre", "ouvrir", "lance", "lancer", "montre", "affiche",
            "joue", "mets", "va sur", "passe sur", "écouter", "regarder", "le", "la", "les", "l'", "chaîne", "station", "application"),
        "es" to listOf("puedes", "por favor", "abre", "abrir", "pon", "poner", "muestra", "reproduce", "inicia", "ve a",
            "cambia a", "escuchar", "ver", "el", "la", "los", "las", "canal", "emisora", "aplicación"),
        "it" to listOf("puoi", "per favore", "apri", "aprire", "metti", "mostra", "riproduci", "avvia", "vai su", "passa a",
            "ascoltare", "guardare", "il", "la", "lo", "i", "le", "canale", "stazione", "app"),
        "pt" to listOf("pode", "por favor", "abre", "abrir", "coloca", "põe", "mostra", "toca", "inicia", "vai para", "muda para",
            "ouvir", "assistir", "o", "a", "os", "as", "canal", "estação", "aplicativo", "app"),
        "nl" to listOf("kun je", "alsjeblieft", "open", "openen", "start", "toon", "laat zien", "speel", "zet", "ga naar",
            "luisteren", "kijken", "de", "het", "zender", "app"),
        "pl" to listOf("możesz", "proszę", "otwórz", "włącz", "pokaż", "puść", "uruchom", "przejdź do", "przełącz na",
            "słuchać", "oglądać", "kanał", "stację", "stacja", "aplikację", "aplikacja"),
        "ru" to listOf("можешь", "пожалуйста", "открой", "включи", "покажи", "запусти", "поставь", "перейди", "переключи на",
            "слушать", "смотреть", "канал", "станцию", "приложение"),
        "uk" to listOf("можеш", "будь ласка", "відкрий", "увімкни", "покажи", "запусти", "постав", "перейди", "перемкни на",
            "слухати", "дивитися", "канал", "станцію", "застосунок", "додаток"),
    )

    /** Bölümler. [takesName]: sonrasında bir ad söylenirse (ör. "Kral FM radyosu") ada göre çalma denenir. */
    private class Section(val id: String, val words: Words, val takesName: Boolean = false)

    private val SECTIONS = listOf(
        Section("youtube", mapOf("*" to listOf("youtube", "you tube", "yutup", "yutub", "ютуб", "ютюб"))),
        Section("netflix", mapOf("*" to listOf("netflix", "netfliks", "нетфликс", "нетфлікс"))),
        Section("disney", mapOf("*" to listOf("disney", "disni", "дисней", "дісней"))),
        Section("prime", mapOf("*" to listOf("prime video", "amazon prime", "prime", "прайм"))),
        Section("tvplus", mapOf("*" to listOf("tv+", "tv plus", "tv artı", "tivi plus", "tiviplus", "tv bplus"))),
        Section("xtream", mapOf("*" to listOf("xtream", "extreme", "ekstrim"))),
        Section("iptv", mapOf("*" to listOf("iptv", "ip tv", "ipitivi", "listelerim"), "en" to listOf("my lists", "playlists"))),
        Section("fm", mapOf("*" to listOf("fm radyo", "fm radio", "fm tuner", "frekans"), "en" to listOf("frequency"), "de" to listOf("frequenz"))),
        Section("mirror", mapOf(
            "tr" to listOf("ekran yansıt", "yansıtma", "yansıt", "ekranı paylaş"), "en" to listOf("screen mirroring", "mirror", "cast screen"),
            "de" to listOf("bildschirm spiegeln", "spiegeln", "spiegelung"), "fr" to listOf("recopie d'écran", "dupliquer l'écran", "miroir"),
            "es" to listOf("duplicar pantalla", "espejo", "proyectar pantalla"), "it" to listOf("duplica schermo", "mirroring"),
            "pt" to listOf("espelhar tela", "espelhamento", "espelhar"), "nl" to listOf("scherm spiegelen", "spiegelen"),
            "pl" to listOf("odbicie ekranu", "kopia ekranu", "lustrzane"), "ru" to listOf("трансляция экрана", "зеркал"),
            "uk" to listOf("трансляція екрана", "дзеркал"))),
        Section("phoneapps", mapOf(
            "tr" to listOf("telefon uygulamaları", "telefondaki uygulamalar", "telefon uygulama"), "en" to listOf("phone apps"),
            "de" to listOf("handy apps", "telefon apps"), "fr" to listOf("applications du téléphone", "applis du téléphone"),
            "es" to listOf("aplicaciones del teléfono"), "it" to listOf("app del telefono"), "pt" to listOf("aplicativos do telefone"),
            "nl" to listOf("telefoon apps"), "pl" to listOf("aplikacje telefonu"), "ru" to listOf("приложения телефона"),
            "uk" to listOf("застосунки телефону"))),
        Section("allapps", mapOf(
            "tr" to listOf("tüm uygulamalar", "uygulamalar", "tümü"), "en" to listOf("all apps", "apps"), "de" to listOf("alle apps", "apps"),
            "fr" to listOf("toutes les applications", "applications"), "es" to listOf("todas las aplicaciones", "aplicaciones"),
            "it" to listOf("tutte le app", "applicazioni"), "pt" to listOf("todos os aplicativos", "aplicativos"),
            "nl" to listOf("alle apps", "apps"), "pl" to listOf("wszystkie aplikacje", "aplikacje"),
            "ru" to listOf("все приложения", "приложения"), "uk" to listOf("усі застосунки", "застосунки"))),
        Section("home", mapOf(
            "tr" to listOf("ana sayfa", "ana ekran", "anasayfa", "başlangıç"), "en" to listOf("home screen", "home"),
            "de" to listOf("startseite", "startbildschirm"), "fr" to listOf("accueil", "écran d'accueil"), "es" to listOf("inicio", "pantalla de inicio"),
            "it" to listOf("home", "schermata iniziale"), "pt" to listOf("início", "tela inicial"), "nl" to listOf("startscherm", "home"),
            "pl" to listOf("ekran główny", "strona główna"), "ru" to listOf("главн"), "uk" to listOf("головн"))),
        Section("weather", mapOf(
            "tr" to listOf("hava durumu", "hava nasıl", "hava"), "en" to listOf("weather", "forecast"), "de" to listOf("wetter"),
            "fr" to listOf("météo"), "es" to listOf("tiempo", "clima"), "it" to listOf("meteo", "tempo"), "pt" to listOf("previsão do tempo", "tempo", "clima"),
            "nl" to listOf("weer"), "pl" to listOf("pogod"), "ru" to listOf("погод"), "uk" to listOf("погод"))),
        Section("equalizer", mapOf(
            "*" to listOf("equalizer", "equaliser"), "tr" to listOf("ekolayzır", "ekolayzer", "ekualayzır", "ekolayzir"),
            "fr" to listOf("égaliseur"), "es" to listOf("ecualizador"), "it" to listOf("equalizzatore"), "pt" to listOf("equalizador"),
            "pl" to listOf("korektor"), "ru" to listOf("эквалайзер"), "uk" to listOf("еквалайзер"))),
        Section("settings", mapOf(
            "tr" to listOf("ayarlar", "ayar"), "en" to listOf("settings", "setting"), "de" to listOf("einstellung"),
            "fr" to listOf("réglages", "paramètres"), "es" to listOf("ajustes", "configuración"), "it" to listOf("impostazioni"),
            "pt" to listOf("configurações", "definições"), "nl" to listOf("instellingen"), "pl" to listOf("ustawienia"),
            "ru" to listOf("настройк"), "uk" to listOf("налаштуван"))),
        Section("vehicle", mapOf(
            "tr" to listOf("araç bilgi", "araç", "araba", "yakıt", "bakım", "kilometre", "yolculuk"), "en" to listOf("vehicle", "car info", "my car", "fuel", "trip"),
            "de" to listOf("fahrzeug", "mein auto", "tank", "fahrt"), "fr" to listOf("véhicule", "ma voiture", "voiture", "carburant"),
            "es" to listOf("vehículo", "mi coche", "coche", "combustible"), "it" to listOf("veicolo", "la mia auto", "carburante"),
            "pt" to listOf("veículo", "meu carro", "carro", "combustível"), "nl" to listOf("voertuig", "mijn auto", "brandstof"),
            "pl" to listOf("pojazd", "samochód", "paliwo"), "ru" to listOf("автомобил", "машин", "топлив"), "uk" to listOf("автомобіл", "машин", "пальн"))),
        Section("favorites", mapOf(
            "tr" to listOf("favoriler", "favori"), "en" to listOf("favorites", "favourites"), "de" to listOf("favoriten"),
            "fr" to listOf("favoris"), "es" to listOf("favoritos"), "it" to listOf("preferiti"), "pt" to listOf("favoritos"),
            "nl" to listOf("favorieten"), "pl" to listOf("ulubione"), "ru" to listOf("избранн"), "uk" to listOf("обран", "улюблен"))),
        Section("media", mapOf(
            "tr" to listOf("medya", "müziklerim", "videolarım", "telefondaki müzik", "müzik"), "en" to listOf("media", "my music", "music"),
            "de" to listOf("medien", "musik"), "fr" to listOf("médias", "musique"), "es" to listOf("medios", "música"),
            "it" to listOf("musica", "media"), "pt" to listOf("mídia", "música"), "nl" to listOf("media", "muziek"),
            "pl" to listOf("multimedia", "muzyk"), "ru" to listOf("медиа", "музык"), "uk" to listOf("медіа", "музик"))),
        Section("podcast", mapOf("*" to listOf("podcast", "podcast'", "подкаст", "pod cast")), takesName = true),
        Section("web", mapOf(
            "*" to listOf("internet", "web", "google"), "tr" to listOf("tarayıcı", "internet tarayıcı"), "en" to listOf("browser"),
            "de" to listOf("browser"), "fr" to listOf("navigateur"), "es" to listOf("navegador"), "it" to listOf("browser"),
            "pt" to listOf("navegador"), "nl" to listOf("browser"), "pl" to listOf("przeglądarka"), "ru" to listOf("браузер", "интернет"),
            "uk" to listOf("браузер", "інтернет"))),
        Section("radio", mapOf("*" to listOf("radio", "radyo", "rádio", "радио", "радіо")), takesName = true),
        Section("tv", mapOf(
            "*" to listOf("tv"), "tr" to listOf("televizyon", "kanallar", "tv kanalları", "tivi"), "en" to listOf("television", "channels"),
            "de" to listOf("fernsehen", "fernseher", "sender"), "fr" to listOf("télévision", "télé", "chaînes"),
            "es" to listOf("televisión", "tele", "canales"), "it" to listOf("televisione", "canali"), "pt" to listOf("televisão", "canais"),
            "nl" to listOf("televisie", "zenders"), "pl" to listOf("telewizja", "telewizor", "kanały"),
            "ru" to listOf("телевизор", "телевидение", "тв", "каналы"), "uk" to listOf("телевізор", "телебачення", "тб", "канали")), takesName = true),
    )

    private fun words(w: Words): List<String> =
        ((w["*"] ?: emptyList()) + (w[I18n.code] ?: emptyList()) + if (I18n.code != "en") (w["en"] ?: emptyList()) else emptyList())
            .sortedByDescending { it.length }

    // ---- Metin işleme ----

    /** Küçük harf, noktalama ve kesme işaretinden sonraki Türkçe ekler atılır ("show tv'yi" → "show tv"). */
    private fun normalize(s: String): String {
        var x = s.lowercase(I18n.locale()).replace('’', '\'').replace('`', '\'')
        if (I18n.code == "tr") x = x.replace(Regex("'\\p{L}+"), "")
        return x.replace(Regex("[.,!?;:\"«»„“”()]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** İfade, metinde sözcük başlarından eşleşiyor mu ("radyo" → "radyoyu" da sayılır)? Eşleşmenin yerini döndürür. */
    private fun find(text: String, phrase: String): IntRange? {
        val p = phrase.lowercase(I18n.locale())
        var from = 0
        while (true) {
            val i = text.indexOf(p, from)
            if (i < 0) return null
            val startOk = i == 0 || !text[i - 1].isLetterOrDigit()
            if (startOk) {
                // Sözcüğün geri kalanı ek olarak sayılır (en fazla 6 harf)
                var end = i + p.length
                var extra = 0
                while (end < text.length && text[end].isLetter() && extra < 6) { end++; extra++ }
                if (end >= text.length || !text[end].isLetterOrDigit()) return i until end
            }
            from = i + 1
        }
    }

    private fun has(text: String, w: Words) = words(w).any { find(text, it) != null }

    private fun remove(text: String, w: Words): String {
        var x = " $text "
        for (p in words(w)) {
            while (true) { val r = find(x, p) ?: break; x = x.removeRange(r).replace(Regex("\\s+"), " ") }
        }
        return x.trim()
    }

    /** Fiil/dolgu sözcükleri ve tek başına kalan Türkçe hal ekleri atılır. */
    private fun clean(text: String): String {
        var x = remove(text, FILLER)
        if (I18n.code == "tr") x = x.split(' ').filterNot { it in TR_SUFFIX }.joinToString(" ")
        return x.trim()
    }

    private val TR_SUFFIX = setOf("yi", "yı", "yu", "yü", "i", "ı", "u", "ü", "nu", "nü", "ni", "nı", "e", "a", "ye", "ya",
        "de", "da", "te", "ta", "mi", "mı", "mu", "mü", "misin", "mısın", "musun", "müsün")

    /** Sayıyla söylenen kanal numaraları ("trt bir" → "trt 1"). */
    private fun digits(s: String): String {
        val map = when (I18n.code) {
            "tr" -> listOf("bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz", "on")
            "en" -> listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")
            "de" -> listOf("eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn")
            else -> emptyList()
        }
        return s.split(' ').joinToString(" ") { w -> map.indexOf(w).takeIf { it >= 0 }?.let { "${it + 1}" } ?: w }
    }

    private fun compact(s: String) = s.lowercase(I18n.locale()).replace(Regex("[^\\p{L}\\p{N}]"), "")
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')

    /** 0..1 benzerlik (1 = aynı). */
    private fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (b.startsWith(a) || a.startsWith(b)) return 0.9 * minOf(a.length, b.length) / maxOf(a.length, b.length) + 0.1
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / maxOf(a.length, b.length)
    }

    private fun <T> best(query: String, items: List<T>, name: (T) -> String, min: Double = 0.72): T? {
        val q = compact(query)
        if (q.length < 2) return null
        return items.map { it to similarity(q, compact(name(it))) }.filter { it.second >= min }.maxByOrNull { it.second }?.first
    }

    // ---- İşleme ----

    fun handle(ui: CarUi, texts: List<String>) {
        val all = texts.map { normalize(it) }.filter { it.isNotBlank() }
        // Önce tanıyıcının tahminlerinden biri doğrudan bir komutsa onu yap
        for (text in all) if (quick(ui, text)) return
        // Değilse en olası tahminden ad araması
        scope.launch { if (!byName(ui, all.first(), null)) unknown(ui, texts.first()) }
    }

    /** Kontrol, arama ve bölüm komutları (internet gerekmeden hemen karar verilir). */
    private fun quick(ui: CarUi, text: String): Boolean {
        val ctx = ui.ctx
        if (has(text, HELP)) { help(ui); return true }
        if (has(text, TIME)) {
            ui.toast(t("Saat {0}", java.text.SimpleDateFormat("HH:mm", I18n.locale()).format(java.util.Date())), long = true); return true
        }
        if (has(text, VOLUME)) { ui.toast(t("Sesi aracın düğmesinden ayarla")); return true }

        // Yansıtmayı durdur ("yansıtmayı kapat") oynatmayı durdurmadan önce
        val mirror = SECTIONS.first { it.id == "mirror" }
        if (has(text, mirror.words) && has(text, PAUSE)) {
            Mirror.stop(ctx.applicationContext); ui.toast(t("Yansıtma durduruldu")); return true
        }

        // Arama: "YouTube'da X ara", "X'i Google'da ara", "radyo X ara"
        if (has(text, SEARCH)) {
            val target = SECTIONS.firstOrNull { it.id in setOf("youtube", "radio", "podcast", "tv") && has(text, it.words) }?.id
            val q = clean(remove(remove(text, SEARCH), target?.let { id -> SECTIONS.first { it.id == id }.words } ?: emptyMap()))
                .let { remove(it, mapOf("tr" to listOf("google'da", "googleda", "google", "internette", "youtube'da", "youtubeda"))) }
            if (q.isNotBlank()) {
                when (target) {
                    "youtube" -> openWeb(ui, WebPage.Mode.YOUTUBE, "https://m.youtube.com/results?search_query=" + enc(q))
                    "radio", "tv", "podcast" -> scope.launch { if (!byName(ui, q, target)) unknown(ui, q) }
                    else -> openWeb(ui, WebPage.Mode.WEB, "https://www.google.com/search?q=" + enc(q))
                }
                ui.toast(t("Aranıyor: {0}", q))
                return true
            }
        }

        val bare = clean(text)
        // Tek başına kontrol sözcükleri
        if (has(text, NEXT) && bare.length <= 3) { PlayerHub.next(); return true }
        if (has(text, PREV) && bare.length <= 3) { PlayerHub.prev(); return true }
        if (has(text, PAUSE) && clean(remove(text, PAUSE)).isBlank()) { if (PlayerHub.playing.value) PlayerHub.toggle(); return true }
        if (has(text, RESUME) && clean(remove(text, RESUME)).isBlank()) { if (!PlayerHub.playing.value) resumeLast(ui); return true }
        if (has(text, BACK) && clean(remove(text, BACK)).isBlank()) { ui.back(); return true }

        // Web kısayolları kendi adlarıyla ("Sahibinden aç")
        best(bare, Prefs.webShortcuts, { it.name }, 0.8)?.let { s ->
            if (bare.isNotBlank()) { openWeb(ui, WebPage.Mode.WEB, s.url); ui.toast(t("{0} açılıyor", s.name)); return true }
        }

        // Bölüm adı geçiyor mu?
        for (sec in SECTIONS) {
            if (!has(text, sec.words)) continue
            val rest = clean(remove(text, sec.words))
            if (sec.takesName && rest.length >= 2) {
                val kind = sec.id
                scope.launch { if (!byName(ui, rest, kind)) openSection(ui, kind) }
                return true
            }
            if (sec.id == "youtube" && rest.length >= 2) {
                openWeb(ui, WebPage.Mode.YOUTUBE, "https://m.youtube.com/results?search_query=" + enc(rest)); return true
            }
            openSection(ui, sec.id)
            return true
        }
        return false
    }

    private fun openSection(ui: CarUi, id: String) {
        when (id) {
            "youtube" -> ui.push(WebPage(ui, WebPage.Mode.YOUTUBE))
            "netflix" -> ui.push(WebPage(ui, WebPage.Mode.NETFLIX))
            "disney" -> ui.push(WebPage(ui, WebPage.Mode.DISNEY))
            "prime" -> ui.push(WebPage(ui, WebPage.Mode.PRIME))
            "tvplus" -> ui.push(WebPage(ui, WebPage.Mode.TVPLUS))
            "xtream" -> ui.push(XtreamHubPage(ui))
            "iptv" -> ui.push(TvPage(ui, TvPage.Source.MINE))
            "tv" -> ui.push(TvHubPage(ui))
            "radio" -> ui.push(RadioPage(ui))
            "fm" -> ui.push(FmTunerPage(ui))
            "podcast" -> ui.push(PodcastPage(ui))
            "mirror" -> ui.push(MirrorPage(ui))
            "phoneapps" -> ui.push(PhoneAppsPage(ui))
            "allapps" -> ui.push(AppsPage(ui))
            "home" -> ui.home()
            "weather" -> ui.push(WeatherPage(ui))
            "equalizer" -> ui.push(EqualizerPage(ui))
            "settings" -> ui.push(SettingsPage(ui))
            "vehicle" -> ui.push(VehiclePage(ui))
            "favorites" -> ui.push(FavoritesPage(ui))
            "media" -> ui.push(LocalMediaPage(ui))
            "web" -> ui.push(WebPage(ui, WebPage.Mode.WEB))
        }
    }

    private fun openWeb(ui: CarUi, mode: WebPage.Mode, url: String) = ui.push(WebPage(ui, mode, url))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Ada göre çal/aç: favori ve son çalınanlar → hazır FM istasyonları → TV kanal listesi →
     * telefon uygulamaları → radyo araması (internet). [kind]: "radio"/"tv"/"podcast" ipucu.
     */
    private suspend fun byName(ui: CarUi, said: String, kind: String?): Boolean {
        val name = digits(clean(said))
        if (name.length < 2) return false

        if (kind == "podcast") { ui.push(PodcastPage(ui)); ui.toast(t("Podcast'te ara: {0}", name)); return true }

        val wantTv = kind == null || kind == "tv"
        val wantRadio = kind == null || kind == "radio"
        val mine = (Prefs.favorites() + Prefs.recents()).distinctBy { it.url }
            .filter { (wantTv && it.kind == Kind.TV) || (wantRadio && it.kind == Kind.RADIO) }
        best(name, mine, { it.title })?.let { play(ui, it, mine); return true }

        if (wantRadio) {
            FmPresets.all().distinctBy { it.title }.let { presets ->
                best(name, presets, { it.title }, 0.8)?.let { p ->
                    FmPresets.resolve(p)?.let { e -> play(ui, e, listOf(e)); return true }
                }
            }
        }

        if (wantTv) {
            val channels = runCatching { Playlists.loadAll(ui.ctx).channels }.getOrDefault(emptyList())
            best(name, channels, { it.title }, 0.78)?.let { play(ui, it, channels); return true }
        }

        if (kind == null) {
            val apps = withContext(Dispatchers.IO) { runCatching { phoneApps(ui.ctx) }.getOrDefault(emptyList()) }
            best(name, apps, { it.first }, 0.8)?.let { (label, pkg) -> openPhoneApp(ui, label, pkg); return true }
        }

        if (wantRadio) {
            val found = runCatching { RadioApi.search(name, limit = 20) }.getOrDefault(emptyList())
            (best(name, found, { it.title }, 0.6) ?: found.firstOrNull().takeIf { kind == "radio" })?.let { play(ui, it, found); return true }
        }
        return false
    }

    private fun play(ui: CarUi, e: MediaEntry, list: List<MediaEntry>) {
        val l = if (e in list) list.filter { it.kind == e.kind } else listOf(e)
        PlayerHub.play(l, l.indexOf(e).coerceAtLeast(0))
        if (e.kind == Kind.TV) ui.push(PlayerPage(ui))
        ui.toast(t("{0} açılıyor", e.title))
    }

    private fun unknown(ui: CarUi, heard: String) =
        ui.toast(t("Anlaşılamadı: \"{0}\". \"Yardım\" de, örnekleri göstereyim.", heard), long = true)

    private fun help(ui: CarUi) = ui.toast(t("Örnekler: \"Show TV aç\", \"Kral FM\", \"hava durumu\", \"ayarlar\", \"ekolayzır\", \"YouTube'da Tarkan ara\", \"Spotify'ı aç\", \"sonraki\", \"durdur\", \"saat kaç\", \"ana sayfa\"."), long = true)
}
