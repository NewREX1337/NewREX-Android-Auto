// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.Brand
import com.newrex.auto.BuildConfig
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Playlists
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.Tile
import com.newrex.auto.ui.TileAdapter
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.neonText
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded
import com.newrex.auto.ui.tileGrid
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Favori kanallar, radyolar ve son çalınanlar tek yerde. */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class FavoritesPage(ui: CarUi) : Page(ui) {
    override val title = t("Favoriler")

    private enum class Tab(val label: String) { ALL(t("Tüm favoriler")), TV("TV"), RADIO(t("Radyo")), RECENT(t("Son çalınanlar")) }

    private var tab = Tab.ALL
    private lateinit var tabs: LinearLayout
    private lateinit var tiles: TileAdapter
    private lateinit var status: TextView

    override fun build(): View = with(ctx) {
        tiles = TileAdapter(this)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        status = label("", 16f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(4))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
                lp(MATCH, WRAP).margins(b = dp(8)))
            addView(FrameLayout(this@with).apply {
                addView(tileGrid(tiles), flp(MATCH, MATCH))
                addView(status, flp(MATCH, WRAP, Gravity.CENTER))
            }, lp(MATCH, 0, 1f))
        }.also {
            scope.launch { Prefs.changes.debounce(150).collect { show() } }
            scope.launch { PlayerHub.now.collect { tiles.highlightKey = it?.key } }
        }
    }

    private fun show(): Unit = with(ctx) {
        tabs.removeAllViews()
        Tab.values().forEach { t ->
            tabs.addView(pill(t.label, selected = t == tab) { tab = t; show() }, lp(WRAP, dp(40)).margins(r = dp(8)))
        }
        val list: List<MediaEntry> = when (tab) {
            Tab.ALL -> Prefs.favorites()
            Tab.TV -> Prefs.favorites(Kind.TV)
            Tab.RADIO -> Prefs.favorites(Kind.RADIO)
            Tab.RECENT -> Prefs.recents()
        }
        val favKeys = Prefs.favorites().map { it.key }.toSet()
        tiles.items = list.mapIndexed { i, e ->
            Tile(e.key, e.title, when (e.kind) { Kind.TV -> "TV"; Kind.RADIO -> t("Radyo"); Kind.PODCAST -> "Podcast" }, e.image,
                com.newrex.auto.ui.kindIcon(e.kind),
                favorite = e.key in favKeys) {
                // Aynı türden öğeler arasında ileri/geri geçilebilsin
                val sameKind = list.filter { it.kind == e.kind }
                PlayerHub.play(sameKind, sameKind.indexOf(e))
                if (e.kind == Kind.TV) ui.push(PlayerPage(ui))
            }
        }
        status.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (tab == Tab.RECENT) t("Henüz bir şey çalmadın.")
        else t("Henüz favori yok.\nÇalarken ☆ simgesine dokunarak ekleyebilirsin.")
    }
}

/** Ayarlar: solda konu menüsü, sağda o konunun ayarları. Değişiklikte sadece sağ taraf yenilenir, kaydırma yeri korunur. */
class SettingsPage(ui: CarUi) : Page(ui) {
    override val title = t("Ayarlar")

    private enum class Cat(val label: String, val icon: Int) {
        // Yanlış dil seçen de bulabilsin diye adı hep İngilizcesiyle birlikte yazılır
        LANG(t("Dil") + if (com.newrex.auto.I18n.code != "en") " · Language" else "", R.drawable.ic_language),
        LOOK(t("Görünüm"), R.drawable.ic_edit),
        SOUND(t("Ses"), R.drawable.ic_tune),
        DRIVE(t("Sürüş"), R.drawable.ic_car),
        TV(t("TV ve listeler"), R.drawable.ic_live_tv),
        WEB(t("Web kısayolları"), R.drawable.ic_language),
        SAVER(t("Pil ve ısınma"), R.drawable.ic_speed),
        ABOUT(t("Hakkında"), R.drawable.ic_info),
    }

    private companion object {
        // Tema değişince arayüz baştan kurulur; aynı konuya geri dönülsün
        var lastCat = Cat.LOOK
    }

    private lateinit var menu: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView

    override fun build(): View = with(ctx) {
        menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val left = panel().apply {
            addView(ScrollView(this@with).apply {
                isVerticalScrollBarEnabled = false
                addView(menu, FrameLayout.LayoutParams(MATCH, WRAP))
            }, lp(MATCH, MATCH))
        }
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(4), dp(16))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(left, lp(dp(220), MATCH).margins(r = dp(12)))
            addView(scroll, lp(0, MATCH, 1f))
            scope.launch {
                ui.compact.collect { c -> left.layoutParams = left.layoutParams.apply { width = dp(if (c) 150 else 220) } }
            }
            show(lastCat, keepScroll = false)
        }
    }

    private fun refresh() = show(lastCat, keepScroll = true)

    private fun show(cat: Cat, keepScroll: Boolean): Unit = with(ctx) {
        lastCat = cat
        menu.removeAllViews()
        Cat.values().forEach { c ->
            val selected = c == cat
            menu.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(8), 0)
                val r = dpf(14)
                background = pressable(rounded(if (selected) Palette.withAlpha(Palette.accent, 0x55) else 0, r,
                    if (selected) dp(1) else 0, Palette.accent), r)
                addView(icon(c.icon, 22, if (selected) Palette.TEXT else Palette.TEXT_DIM), lp(dp(22), dp(22)))
                addView(label(c.label, 16f, if (selected) Palette.TEXT else Palette.TEXT_DIM, bold = selected),
                    lp(0, WRAP, 1f).margins(l = dp(12)))
                onTap(true) { if (lastCat != c) show(c, keepScroll = false) }
            }, lp(MATCH, dp(52)).margins(b = dp(4)))
        }
        val y = scroll.scrollY
        body.removeAllViews()
        when (cat) {
            Cat.LANG -> language()
            Cat.LOOK -> look()
            Cat.SOUND -> sound()
            Cat.DRIVE -> drive()
            Cat.TV -> tv()
            Cat.WEB -> web()
            Cat.SAVER -> saver()
            Cat.ABOUT -> about()
        }
        scroll.post { scroll.scrollTo(0, if (keepScroll) y else 0) }
    }

    // ---- Konular ----

    /** Dil seçimi: büyük, bayraklı düğmeler; iki sütun. Seçilince uygulama yeni dille yeniden açılır. */
    private fun language(): Unit = with(ctx) {
        val I = com.newrex.auto.I18n
        val choice = I.choice(this)
        val flags = mapOf("tr" to "🇹🇷", "en" to "🇬🇧", "de" to "🇩🇪", "fr" to "🇫🇷", "es" to "🇪🇸", "it" to "🇮🇹",
            "pt" to "🇵🇹", "nl" to "🇳🇱", "pl" to "🇵🇱", "ru" to "🇷🇺", "uk" to "🇺🇦")
        val all = listOf(I.AUTO to "📱  " + t("Otomatik")) + I.LANGS.map { it.code to "${flags[it.code] ?: "🌐"}  ${it.name}" }
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        all.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, (code, name) ->
                val sel = code == choice
                val r = dpf(16)
                row.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(18), 0, dp(12), 0)
                    background = pressable(rounded(if (sel) Palette.withAlpha(Palette.accent, 0x55) else Palette.SURFACE_STRONG, r,
                        if (sel) dp(2) else 0, Palette.accent), r)
                    addView(label(name, 18f, bold = sel), lp(0, WRAP, 1f))
                    if (sel) addView(icon(R.drawable.ic_check, 22, Palette.accent), lp(dp(22), dp(22)))
                    onTap {
                        if (sel) return@onTap
                        I.setChoice(ctx, code)
                        ui.toast(t("Dil değişti. NewREX yeniden başlıyor, araç ekranından tekrar aç."), long = true)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 2500)
                    }
                }, lp(0, dp(58), 1f).margins(l = if (i > 0) dp(10) else 0))
            }
            if (pair.size == 1) row.addView(View(this), lp(0, dp(58), 1f).margins(l = dp(10)))
            grid.addView(row, lp(MATCH, WRAP).margins(b = dp(10)))
        }
        body.addView(section("🌐 " + t("Dil") + if (I.code != "en") " · Language" else "", grid))
    }

    private fun look(): Unit = with(ctx) {
        val current = Palette.accent
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Palette.accents.forEachIndexed { i, c ->
            colors.addView(swatch(c, i == Prefs.accentIndex) { Prefs.accentIndex = i }, lp(dp(48), dp(48)).margins(r = dp(12)))
        }
        Palette.extraAccents.forEach { c ->
            colors.addView(swatch(c, Prefs.accentIndex < 0 && c == current) { Prefs.customAccent = c },
                lp(dp(48), dp(48)).margins(r = dp(12)))
        }
        val customAccent = Prefs.accentIndex < 0 && current !in Palette.extraAccents
        colors.addView(customSwatch(if (customAccent) current else null) {
            ui.push(ColorPickerPage(ui, t("Özel tema rengi"), current, 1f) { Prefs.customAccent = it })
        }, lp(dp(48), dp(48)))

        val bgs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Palette.backgrounds.forEach { c ->
            val shown = if (c == 0) Palette.BG_TOP else c
            bgs.addView(swatch(shown, Prefs.bgColor == c, text = if (c == 0) t("Oto") else null) { Prefs.bgColor = c },
                lp(dp(48), dp(48)).margins(r = dp(12)))
        }
        val customBg = Prefs.bgColor != 0 && Prefs.bgColor !in Palette.backgrounds
        bgs.addView(customSwatch(if (customBg) Palette.safeBackground(Prefs.bgColor) else null) {
            val start = Palette.safeBackground(Prefs.bgColor).takeIf { it != 0 } ?: Palette.BG_TOP
            ui.push(ColorPickerPage(ui, t("Özel arka plan"), start, Palette.MAX_BG_VALUE) { Prefs.bgColor = it })
        }, lp(dp(48), dp(48)))

        // Hazır arka planlar: dalga hareketli, diğerleri sabit resim (telefonu yormaz)
        body.addView(section(t("Arka plan stili"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = com.newrex.auto.ui.WrapLayout(this@with, dp(12), dp(14))
            val photo = com.newrex.auto.data.Background.has(ctx)
            val tw = dp(150); val th = dp(66)
            com.newrex.auto.ui.Backdrop.NAMES.forEachIndexed { i, name ->
                val sel = !photo && Prefs.bgStyle == i
                val r = dpf(14)
                row.addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(android.widget.ImageView(this@with).apply {
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(com.newrex.auto.ui.Backdrop.thumb(if (i == 0) 5 else i, tw, th, Palette.accent))
                        outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
                        }
                        clipToOutline = true
                        foreground = rounded(0, r, if (sel) dp(3) else dp(1), if (sel) Palette.accent else 0x33FFFFFF)
                    }, lp(tw, th))
                    addView(label(name + if (i == 0) " ✦" else "", 13f, if (sel) Palette.TEXT else Palette.TEXT_DIM, bold = sel),
                        lp(WRAP, WRAP).margins(t = dp(6)))
                    onTap {
                        if (photo) com.newrex.auto.data.Background.remove(ctx)
                        Prefs.bgStyle = i
                    }
                }, android.view.ViewGroup.LayoutParams(WRAP, WRAP))
            }
            addView(row, lp(MATCH, WRAP))
            addView(hint(t("✦ Dalga hareketlidir; diğerleri sabittir ve telefonu daha az yorar. Renkler tema renginden gelir.")),
                lp(MATCH, WRAP).margins(t = dp(10)))
        }))

        // Özel arka plan: telefondan seçilen fotoğraf
        body.addView(section(t("Arka plan fotoğrafı"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val has = com.newrex.auto.data.Background.has(ctx)
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(pill(t("Telefondan seç"), R.drawable.ic_photo) {
                    if (com.newrex.auto.DriveState.driving.value) { ui.toast(t("Fotoğrafı park halindeyken seçebilirsin.")); return@pill }
                    com.newrex.auto.PhotoPickActivity.startBackground(ctx.applicationContext)
                    ui.toast(t("Telefonunda fotoğraf seçme ekranı açıldı."), long = true)
                }, lp(WRAP, dp(44)))
                if (has) addView(pill(t("Kaldır"), R.drawable.ic_close) { com.newrex.auto.data.Background.remove(ctx) },
                    lp(WRAP, dp(44)).margins(l = dp(10)))
            })
            addView(hint(t("En iyi sonuç: yatay, en az 1920×720 (geniş ekran) ya da 1920×1080 fotoğraf. Fotoğraf ekranı dolduracak şekilde ortadan kırpılır; önemli kısmı ortada olsun. Koyu ve sade fotoğraflarda yazılar daha iyi okunur.")),
                lp(MATCH, WRAP).margins(t = dp(10)))
        }))

        body.addView(section(t("Tema rengi"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(colors) })
            addView(label(t("Arka plan"), 15f, bold = true), lp(MATCH, WRAP).margins(t = dp(16), b = dp(10)))
            addView(HorizontalScrollView(this@with).apply { isHorizontalScrollBarEnabled = false; addView(bgs) })
            addView(hint(t("Gökkuşağı düğmesiyle istediğin her rengi seçebilirsin.")), lp(MATCH, WRAP).margins(t = dp(12)))
        }))
        body.addView(toggle(t("Tam ekran ana sayfa"),
            t("Ana ekranda üst bar ve mini oynatıcı gizlenir, kartlar büyür. Diğer sayfalarda geri tuşu için üst bar görünür kalır."),
            Prefs.immersiveMode) { Prefs.immersiveMode = it; ui.refreshChrome() })

        body.addView(section(t("Ana ekran kart sırası"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val ids = listOf("tv", "youtube", "radio", "web")
            val names = mapOf("tv" to "TV", "youtube" to "YouTube", "radio" to t("Radyo"), "web" to "Web")
            val order = Prefs.cardOrder.let { if (it.size == 4 && it.toSet() == ids.toSet()) it else ids }
            order.forEachIndexed { i, id ->
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(4), dp(4), 0, dp(4))
                    addView(label("${i + 1}. ${names[id] ?: id}", 16f, bold = true), lp(0, WRAP, 1f))
                    fun move(to: Int) {
                        val list = order.toMutableList()
                        list[i] = list[to].also { list[to] = list[i] }
                        Prefs.cardOrder = list
                        refresh()
                    }
                    if (i > 0) addView(circleButton(R.drawable.ic_up, 40) { move(i - 1) }, lp(dp(40), dp(40)).margins(r = dp(8)))
                    else addView(View(this@with), lp(dp(40), dp(40)).margins(r = dp(8)))
                    if (i < order.lastIndex) addView(circleButton(R.drawable.ic_down, 40) { move(i + 1) }, lp(dp(40), dp(40)))
                    else addView(View(this@with), lp(dp(40), dp(40)))
                }, lp(MATCH, WRAP))
            }
            addView(hint(t("Ana ekrandaki dört büyük kartın sırası.")), lp(MATCH, WRAP).margins(t = dp(8)))
        }))
    }

    private fun sound(): Unit = with(ctx) {
        // "Hey <kelime>" ile uyandırma: kelimeyi kullanıcı seçer
        val wake = com.newrex.auto.WakeWord.word
        body.addView(toggle(t("\"Hey {0}\" ile uyandır", wake),
            t("\"Hey {0}\" deyince \"Sizi dinliyorum\" der ve komutunu bekler. Aynı cümlede komutu da söyleyebilirsin: \"Hey {0}, hava durumu\".", wake) +
                " " + t("Açıkken telefon sürekli dinlediği için işlemciyi biraz yorar."),
            Prefs.wakeEnabled, action = t("Kelimeyi değiştir") to {
                ui.showKeyboard(t("\"Hey\"den sonra söylenecek kelime"), initial = Prefs.wakeWord, doneLabel = t("Kaydet")) { w ->
                    Prefs.wakeWord = w.lowercase(com.newrex.auto.I18n.locale())
                    com.newrex.auto.WakeWord.settingsChanged()
                    ui.toast(t("Artık \"Hey {0}\" de", Prefs.wakeWord))
                    refresh()
                }
            }) {
            Prefs.wakeEnabled = it
            com.newrex.auto.WakeWord.settingsChanged()
        })
        body.addView(toggle(t("Ekolayzır"), t("5 bantlı ekolayzır. Radyo, TV ve podcast'te çalışır."), Prefs.eqEnabled,
            action = if (Prefs.eqEnabled) t("Ayarla") to { ui.push(EqualizerPage(ui)) } else null) {
            Prefs.eqEnabled = it
            com.newrex.auto.PlayerHub.applyEqualizer()
        })
        body.addView(section(t("Hıza göre ses"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hint(t("Hızlandıkça yol ve rüzgâr gürültüsüne karşı TV, radyo ve podcast sesini biraz açar, yavaşlayınca eski haline getirir. 30 km/s altında değişmez.")))
            addView(choices(com.newrex.auto.SpeedVolume.levelNames(), Prefs.speedVolume) {
                Prefs.speedVolume = it
                com.newrex.auto.SpeedVolume.settingsChanged()
            }, lp(MATCH, WRAP).margins(t = dp(10)))
        }), lp(MATCH, WRAP).margins(b = dp(12)))
        body.addView(toggle(t("Sesli karşılama"), t("Araca bağlanınca \"Günaydın, NewREX'e hoş geldin!\" der. Mikrofon gerekmez."),
            Prefs.greetingEnabled) { Prefs.greetingEnabled = it })
        if (Prefs.greetingEnabled) {
            body.addView(section(t("Karşılama ayarları"), LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(t("İsim"), 15f, Palette.TEXT_DIM, bold = true))
                addView(pill(Prefs.greetingName.ifBlank { t("İsim gir…") }, R.drawable.ic_edit) {
                    ui.showKeyboard(t("İsmin"), initial = Prefs.greetingName, doneLabel = t("Kaydet"), allowEmpty = true) { name ->
                        Prefs.greetingName = name
                        refresh()
                    }
                }, lp(WRAP, dp(44)).margins(t = dp(8)))
                addView(label(t("Ne sıklıkla"), 15f, Palette.TEXT_DIM, bold = true), lp(MATCH, WRAP).margins(t = dp(14)))
                addView(choices(listOf(t("Her bağlantıda"), t("Günde bir kez")), if (Prefs.greetingOncePerDay) 1 else 0) {
                    Prefs.greetingOncePerDay = it == 1
                }, lp(MATCH, WRAP).margins(t = dp(8)))
            }))
        }
    }

    private fun drive(): Unit = with(ctx) {
        body.addView(toggle(t("Park modu"), t("Açıkken araç hareket edince video ve web görüntüsü gizlenir, ses devam eder. ") +
            t("Kapatırsan sürüşte de görüntü açık kalır. Dikkatli kullan!"), Prefs.parkModeVideo) { Prefs.parkModeVideo = it })
        body.addView(hint(t("Hız araçtan alınamazsa telefonun GPS'inden ölçülür (telefonda konum izni gerekir). ") +
            t("Hız sınırı uyarısı ve bakım bilgileri Araç sayfasında.")), lp(MATCH, WRAP).margins(l = dp(4)))
    }

    private fun tv(): Unit = with(ctx) {
        body.addView(section(t("IPTV listeleri"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(Prefs.playlists.joinToString("\n") { "• ${it.name}" }.ifEmpty { t("Henüz liste yok.") },
                15f, Palette.TEXT_DIM, lines = 8))
            addView(hint(t("Liste eklemek veya silmek için telefonda NewREX uygulamasını aç.")), lp(MATCH, WRAP).margins(t = dp(8)))
            addView(pill(t("Kanalları yeniden indir"), R.drawable.ic_refresh) {
                scope.launch {
                    ui.toast(t("Listeler yenileniyor…"))
                    val r = Playlists.loadAll(ctx, forceRefresh = true)
                    ui.toast(t("{0} kanal yüklendi", r.channels.size) + if (r.errors.isNotEmpty()) t(" ({0} liste hatalı)", r.errors.size) else "")
                }
            }, lp(WRAP, dp(44)).margins(t = dp(12)))
        }))
        body.addView(section(t("TV görüntü kalitesi"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(choices(com.newrex.auto.PlayerHub.QUALITIES.map { it.first }, Prefs.tvQuality) { Prefs.tvQuality = it })
            addView(hint(t("Otomatik: yayının en iyi kalitesi. Düşük seçersen telefon daha az ısınır, daha az internet harcar. ") +
                t("Değişiklik açık kanala hemen uygulanır.")), lp(MATCH, WRAP).margins(t = dp(10)))
        }))
    }

    private fun web(): Unit = with(ctx) {
        body.addView(section(t("Web kısayolları"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val count = Prefs.webShortcuts.size
            addView(label(if (count > 0) t("{0} kısayol kayıtlı", count) else t("Henüz kısayol yok"), 15f, Palette.TEXT_DIM))
            addView(pill(t("Kısayolları düzenle"), R.drawable.ic_edit) { ui.push(WebShortcutsPage(ui)) },
                lp(WRAP, dp(44)).margins(t = dp(10)))
            addView(hint(t("Sık girdiğin siteleri ekle (en fazla 6). Ana ekranda \"Tümü\" → İnternet bölümünde görünürler.")),
                lp(MATCH, WRAP).margins(t = dp(10)))
        }))
        body.addView(section("YouTube", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pill(t("YouTube geçmişini sıfırla"), R.drawable.ic_refresh) {
                com.newrex.auto.data.YouTubeReset.run()
                android.webkit.WebView(ctx).apply { clearCache(true); destroy() }
                ui.toast(t("YouTube çerezleri ve izleme geçmişi silindi. Öneriler sıfırdan başlayacak."))
            }, lp(WRAP, dp(44)))
            addView(hint(t("Sadece NewREX'teki YouTube silinir; TV+, Netflix gibi girişlerin kalır.")), lp(MATCH, WRAP).margins(t = dp(10)))
        }))
    }

    private fun saver(): Unit = with(ctx) {
        body.addView(section(t("Tasarruf modu"), LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(choices(com.newrex.auto.Saver.NAMES.toList(), Prefs.saverMode) { Prefs.saverMode = it })
            val why = com.newrex.auto.Saver.reason()
            addView(hint((if (why != null) t("Şu an açık ({0}). ", why) else t("Şu an kapalı. ")) +
                t("Açıkken arka plan sabit durur, dokununca çıkan efektler kapanır. Görüntü ve ses kalitesi değişmez. ") +
                t("Otomatik: telefon pil tasarrufundayken ya da ısındığında kendiliğinden açılır.")), lp(MATCH, WRAP).margins(t = dp(10)))
        }))
    }

    private fun about(): Unit = with(ctx) {
        body.addView(section("NewREX", LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(t("Sürüm {0}", BuildConfig.VERSION_NAME), 16f, bold = true))
            addView(label(t("Orijinal geliştirici: {0}", Brand.ORIGINAL_DEVELOPER), 15f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(6)))
            addView(label(t("Reklam yok  •  Takip yok  •  Veri toplanmaz"), 15f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = dp(6)))
            addView(pill(t("Ayrıntılar ve lisans"), R.drawable.ic_info) { ui.push(AboutPage(ui)) }, lp(WRAP, dp(44)).margins(t = dp(12)))
        }))
    }

    // ---- Yardımcılar ----

    private fun hint(text: String) = ctx.label(text, 13f, Palette.TEXT_FAINT, lines = 4)

    /** Başlık + açıklama solda, Açık/Kapalı düğmesi sağda; [action] verilirse yanında ek bir düğme. */
    private fun toggle(
        title: String, desc: String, on: Boolean,
        action: Pair<String, () -> Unit>? = null,
        onChange: (Boolean) -> Unit,
    ): View = with(ctx) {
        panel(orientationVertical = false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(14), dp(14))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 17f, bold = true))
                addView(label(desc, 13f, Palette.TEXT_DIM, lines = 4), lp(MATCH, WRAP).margins(t = dp(4)))
            }, lp(0, WRAP, 1f).margins(r = dp(12)))
            if (action != null) addView(pill(action.first) { action.second() }, lp(WRAP, dp(44)).margins(r = dp(8)))
            addView(pill(if (on) t("Açık") else t("Kapalı"), if (on) R.drawable.ic_check else null, selected = on) {
                onChange(!on)
                refresh()
            }, lp(WRAP, dp(44)))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(12))
        }
    }

    /** Yan yana seçenekler; dar ekranda yana kayar. */
    private fun choices(names: List<String>, selected: Int, onPick: (Int) -> Unit): View = with(ctx) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        names.forEachIndexed { i, n ->
            row.addView(pill(n, selected = i == selected) { if (i != selected) { onPick(i); refresh() } },
                lp(WRAP, dp(44)).margins(r = dp(10)))
        }
        HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** Yuvarlak renk seçici; seçili olan beyaz çerçeveli. */
    private fun swatch(color: Int, selected: Boolean, text: String? = null, onPick: () -> Unit): View = with(ctx) {
        FrameLayout(this).apply {
            val r = dpf(24)
            background = pressable(rounded(color, r, if (selected) dp(4) else dp(1), if (selected) Palette.TEXT else 0x40FFFFFF), r)
            if (text != null) addView(label(text, 12f, bold = true).apply { gravity = Gravity.CENTER }, flp(MATCH, MATCH))
            onTap(onPick)
        }
    }

    /** Gökkuşağı daire: istenen herhangi bir rengi seçmek için. Özel renk seçiliyse o renkle çerçevelenir. */
    private fun customSwatch(selected: Int?, onPick: () -> Unit): View = with(ctx) {
        FrameLayout(this).apply {
            val r = dpf(24)
            background = pressable(com.newrex.auto.ui.ColorPickerView.rainbowDot(), r)
            if (selected != null) {
                addView(View(this@with).apply { background = rounded(selected, dpf(14), dp(3), Palette.TEXT) },
                    flp(dp(28), dp(28), Gravity.CENTER))
            } else {
                addView(label("+", 22f, bold = true).apply { gravity = Gravity.CENTER; setShadowLayer(dpf(4), 0f, 0f, 0xAA000000.toInt()) },
                    flp(MATCH, MATCH))
            }
            onTap(onPick)
        }
    }

    private fun section(title: String, body: View): View = with(ctx) {
        panel().apply {
            setPadding(dp(18), dp(14), dp(18), dp(16))
            addView(label(title, 17f, bold = true), lp(MATCH, WRAP).margins(b = dp(12)))
            addView(body, lp(MATCH, WRAP))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).margins(b = dp(12))
        }
    }
}

class AboutPage(ui: CarUi) : Page(ui) {
    override val title = t("Hakkında")

    override fun build(): View = with(ctx) {
        val logo = SpannableStringBuilder("NewREX").apply {
            setSpan(ForegroundColorSpan(Palette.accent), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(label(logo, 52f, bold = true).apply { neonText(14) }, lp(WRAP, WRAP))
            addView(label("Android Auto", 20f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(2)))
            addView(label(t("Sürüm {0}", BuildConfig.VERSION_NAME), 14f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(t = dp(14)))
            addView(label(t("Orijinal geliştirici: {0}", Brand.ORIGINAL_DEVELOPER), 18f, bold = true), lp(WRAP, WRAP).margins(t = dp(16)))
            addView(label(t("Reklam yok  •  Takip yok  •  Veri toplanmaz"), 15f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = dp(8)))
            addView(label(Brand.COPYRIGHT, 13f, Palette.TEXT_FAINT), lp(WRAP, WRAP).margins(t = dp(10)))
            addView(label(Brand.LICENSE_NOTICE, 12f, Palette.TEXT_FAINT, lines = 3).apply { gravity = Gravity.CENTER },
                lp(MATCH, WRAP).margins(dp(30), dp(4), dp(30), 0))
        }
        FrameLayout(this).apply {
            addView(info, flp(MATCH, MATCH))
            // Yukarı kaydırınca uygulamalara dönülür
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon(R.drawable.ic_up, 22, Palette.TEXT_FAINT), lp(dp(22), dp(22)))
                addView(label(t("Uygulamalar"), 13f, Palette.TEXT_FAINT), lp(WRAP, WRAP))
            }, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(t = dp(12)))
        }
    }
}

/** Araç ekranında renk seçme sayfası: kareye ve renk çubuğuna dokunarak seçilir, "Uygula" ile kaydedilir. */
class ColorPickerPage(
    ui: CarUi,
    override val title: String,
    private val initial: Int,
    private val maxValue: Float,
    private val onApply: (Int) -> Unit,
) : Page(ui) {
    override fun build(): View = with(ctx) {
        val picker = com.newrex.auto.ui.ColorPickerView(this, initial, maxValue)
        val preview = View(this)
        val hexLabel = label("", 18f, bold = true)
        fun show(c: Int) {
            preview.background = rounded(c, dpf(40), dp(3), 0x55FFFFFF)
            hexLabel.text = com.newrex.auto.ui.ColorPickerView.hex(c)
        }
        show(picker.color)
        picker.onChange = { show(it) }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(preview, lp(dp(80), dp(80)))
            addView(hexLabel, lp(WRAP, WRAP).margins(t = dp(10)))
            addView(pill(t("Uygula"), R.drawable.ic_check, selected = true) {
                ui.pop()
                onApply(picker.color)
            }, lp(WRAP, dp(48)).margins(t = dp(18)))
            addView(pill(t("Vazgeç")) { ui.pop() }, lp(WRAP, dp(44)).margins(t = dp(10)))
        }
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(4), dp(16), dp(12))
            addView(picker, lp(0, MATCH, 1f))
            addView(side, lp(dp(170), MATCH).margins(l = dp(12)))
        }
    }
}
