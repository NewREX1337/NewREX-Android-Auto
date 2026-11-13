// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import com.newrex.auto.t
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.newrex.auto.DriveState
import com.newrex.auto.VehicleData
import com.newrex.auto.WeatherHub
import com.newrex.auto.data.CarLog
import com.newrex.auto.data.CarPhoto
import com.newrex.auto.data.WeatherApi
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.CardArt
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.gradient
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.onTap
import com.newrex.auto.ui.pressable
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Uygulamalar sayfası. Geniş ekranda tek ekrana sığan pano (solda araç, ortada dört büyük kart, sağda hava
 * ve devam et, altta kısayollar); bölünmüş dar ekranda kaydırılan basit kart listesi.
 */
@OptIn(FlowPreview::class)
class HomePage(ui: CarUi) : Page(ui) {
    override val title = ""

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_settings) { ui.push(SettingsPage(ui)) },
    )

    private class Shortcut(
        val title: String, val subtitle: String, val icon: Int, val color: Int,
        val warn: Boolean = false, val onClick: () -> Unit,
    )

    private companion object {
        // Panonun tasarım ölçüleri; ekrana göre [scale] ile büyütülüp küçültülür
        const val DESIGN_W = 1528f
        const val DESIGN_H = 490f
        // Orta boy ekran (8 inç): araç paneli + 4 kart, altta tam genişlikte kısayollar; hava ve "devam et" kartı yok
        const val MID_W = 1120f
        const val CARD = 0xFF0C0E14.toInt()
        const val LINE = 0x1FFFFFFF
    }

    private val tr = com.newrex.auto.I18n.locale()
    private lateinit var root: FrameLayout
    private lateinit var container: LinearLayout
    private var lastWidth = 0
    private var lastHeight = 0
    private var scale = 1f

    // Panoda canlı güncellenen yazılar
    private var carImage: ImageView? = null
    private var speedValue: TextView? = null
    private var tripValue: TextView? = null
    private var careValue: TextView? = null
    private var careBar: ProgressBar? = null
    private var contTitle: TextView? = null
    private var contSub: TextView? = null
    private var contPlay: ImageView? = null
    private var contProgress: View? = null
    private var contBar: ProgressBar? = null
    private var contElapsed: TextView? = null
    private var contTotal: TextView? = null

    override fun build(): View = with(ctx) {
        root = FrameLayout(this).apply {
            // Ölçüler ekran boyutuna göre; boyut değişince yeniden diz
            addOnLayoutChangeListener { v, l, t, rr, b, _, _, _, _ ->
                val w = rr - l
                val h = b - t
                if (w > 0 && h > 0 && (w != lastWidth || h != lastHeight)) {
                    lastWidth = w; lastHeight = h
                    v.post { populate(ui.compact.value) }
                }
            }
        }
        // Her ayar değişikliğinde (favori, son çalınan...) bütün pano baştan kurulup ekran takılıyordu: sadece
        // panoda görünen bilgiler değişince yeniden kurulur, geri kalanı yerinde güncellenir
        scope.launch {
            combine(ui.compact, Prefs.changes.map { boardSignature() }.distinctUntilChanged(), WeatherHub.report) { c, _, _ -> c }
                .debounce(150)
                .collect { c -> populate(c) }
        }
        scope.launch { Prefs.changes.collect { showContinue(); showCare(DriveState.tripMeters.value / 1000f) } }
        CarPhoto.load(this)
        com.newrex.auto.data.CarFrames.load(applicationContext)
        scope.launch { CarPhoto.image.collect { carImage?.setImageBitmap(it) } }
        scope.launch { DriveState.speedKmh.collect { showSpeed() } }
        scope.launch { DriveState.tripMeters.collect { showTrip() } }
        scope.launch {
            combine(PlayerHub.now, PlayerHub.playing, PlayerHub.position, PlayerHub.duration) { _, _, _, _ -> }.collect { showContinue() }
        }
        root
    }

    // Araç sayfasında bakım bilgisi değişmiş olabilir; ilk açılışta internet yoksa fotoğraf yeniden denenir
    override fun onShow() {
        showTrip()
        CarPhoto.load(ctx)
    }

    /** Panonun yeniden kurulmasını gerektiren ayarlar: kart sırası, (dar ekranda) son çalınan ve bakım uyarısı. */
    private fun boardSignature(): String =
        Prefs.cardOrder.joinToString(",") + "|" + Prefs.recents().firstOrNull()?.key + "|" + Prefs.maintenanceDue().firstOrNull()

    private fun populate(compact: Boolean) {
        if (lastWidth == 0) return
        root.removeAllViews()
        if (compact) compactList() else board()
        showSpeed(); showTrip(); showContinue()
    }

    private data class CardDef(val id: String, val title: String, val subtitle: String, val icon: Int, val color: Int, val art: CardArt.Kind, val onClick: () -> Unit)

    private fun allCardDefs() = listOf(
        CardDef("tv", "TV", t("Kanallar, IPTV, TV+"), R.drawable.ic_live_tv, 0xFF0EA5E9.toInt(), CardArt.Kind.TV) { ui.push(TvHubPage(ui)) },
        CardDef("youtube", "YouTube", t("Video izle"), R.drawable.ic_smart_display, 0xFFE11D2E.toInt(), CardArt.Kind.YOUTUBE) { ui.push(WebPage(ui, WebPage.Mode.YOUTUBE)) },
        CardDef("radio", t("Radyo"), t("Türk radyoları"), R.drawable.ic_radio, 0xFF8B3CF6.toInt(), CardArt.Kind.RADIO) { ui.push(RadioPage(ui)) },
        CardDef("web", "Web", t("İnternet tarayıcı"), R.drawable.ic_language, 0xFF2563EB.toInt(), CardArt.Kind.WEB) { ui.push(WebPage(ui, WebPage.Mode.WEB)) },
    )

    private fun orderedCardDefs(): List<CardDef> {
        val defs = allCardDefs()
        val order = Prefs.cardOrder
        if (order.isEmpty()) return defs
        val byId = defs.associateBy { it.id }
        val sorted = order.mapNotNull { byId[it] }
        val remaining = defs.filter { d -> sorted.none { it.id == d.id } }
        return sorted + remaining
    }

    private fun orderedCards(): List<View> = with(ctx) {
        orderedCardDefs().map { d -> wideCard(d.title, d.subtitle, d.icon, d.color, d.art, d.onClick) }
    }

    // ---- Geniş ekran panosu ----

    private fun u(v: Float) = (v * scale).toInt().coerceAtLeast(1)

    private fun text(s: CharSequence, size: Float, color: Int = Palette.TEXT, bold: Boolean = false) =
        ctx.label(s, size, color, bold).apply { setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale) }

    /**
     * Geniş ekranda araç paneli solda; orta boy ekranda araç paneli kalkar, yerine alt sırada "Araç" kısayolu
     * çıkar. Pano okunamayacak kadar küçülecekse kaydırılan listeye geçilir (taşma ve kesik yazı olmasın).
     */
    private fun board(): Unit = with(ctx) {
        val pad = dp(8)
        val w = (lastWidth - pad * 2).toFloat()
        val h = (lastHeight - pad * 2).toFloat()
        val minScale = dpf(0.6f)
        val fitWide = minOf(w / DESIGN_W, h / DESIGN_H)
        val fitMid = minOf(w / MID_W, h / DESIGN_H)
        val wide = fitWide >= minScale && fitWide >= fitMid * 0.8f
        if (!wide && fitMid < minScale) { compactList(); return }
        scale = if (wide) fitWide else fitMid
        val gap = u(14f)
        if (!wide) { midBoard(pad, gap); return }

        val cards = orderedCards()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardRow(gap, cards[0], cards[1]), lp(MATCH, 0, 1f))
            addView(cardRow(gap, cards[2], cards[3]), lp(MATCH, 0, 1f).margins(t = gap))
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(weatherCard(), lp(MATCH, 0, 166f))
            addView(continueCard(), lp(MATCH, 0, 200f).margins(t = gap))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(grid, lp(0, MATCH, 703f))
            addView(side, lp(0, MATCH, 358f).margins(l = gap))
        }
        // Alt sıra az ve yazılı: geri kalan her şey "Tümü"nde, sesli komut ve ayarlar üst barda
        val shortcuts = mutableListOf(
            shortcutCard(t("Favoriler"), R.drawable.ic_star, 0xFF7C4A14.toInt(), 0xFFFFA726.toInt()) { ui.push(FavoritesPage(ui)) },
            shortcutCard(t("Ayarlar"), R.drawable.ic_settings, 0xFF475569.toInt()) { ui.push(SettingsPage(ui)) },
            shortcutCard(t("Medya"), R.drawable.ic_photo, 0xFF059669.toInt()) { ui.push(LocalMediaPage(ui)) },
        )
        shortcuts += shortcutCard(t("Tümü"), R.drawable.ic_apps, 0xFF334155.toInt()) { ui.push(AppsPage(ui)) }
        val bottom = cardRow(gap, *shortcuts.toTypedArray())
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(top, lp(MATCH, 0, 382f))
            addView(bottom, lp(MATCH, 0, 94f).margins(t = gap))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            addView(carPanel(), lp(0, MATCH, 402f))
            addView(right, lp(0, MATCH, 1110f).margins(l = gap))
        }, flp(MATCH, MATCH))
    }

    /**
     * 8 inç gibi orta boy ekranlar: solda araç fotoğrafı (başlıksız, fotoğraf büyük kalsın), sağda dört büyük
     * kart; altta ekran boyunca kısayollar ve hava durumu. Çalan şey zaten alttaki mini oynatıcıda görünür.
     */
    private fun midBoard(pad: Int, gap: Int): Unit = with(ctx) {
        contTitle = null; contSub = null; contPlay = null; contProgress = null; contBar = null
        val cards = orderedCards()
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardRow(gap, cards[0], cards[1]), lp(MATCH, 0, 1f))
            addView(cardRow(gap, cards[2], cards[3]), lp(MATCH, 0, 1f).margins(t = gap))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(carPanel(header = false), lp(0, MATCH, 402f))
            addView(grid, lp(0, MATCH, 703f).margins(l = gap))
        }
        val w = WeatherHub.report.value
        val bottom = cardRow(gap,
            shortcutCard(t("Favoriler"), R.drawable.ic_star, 0xFF7C4A14.toInt(), 0xFFFFA726.toInt()) { ui.push(FavoritesPage(ui)) },
            shortcutCard(t("Ayarlar"), R.drawable.ic_settings, 0xFF475569.toInt()) { ui.push(SettingsPage(ui)) },
            shortcutCard(t("Medya"), R.drawable.ic_photo, 0xFF059669.toInt()) { ui.push(LocalMediaPage(ui)) },
            shortcutCard(w?.let { "${WeatherApi.emoji(it.code, it.isDay)} ${it.temp}°" } ?: t("Hava"),
                R.drawable.ic_cloud, 0xFF0369A1.toInt()) { ui.push(WeatherPage(ui)) },
            shortcutCard(t("Tümü"), R.drawable.ic_apps, 0xFF334155.toInt()) { ui.push(AppsPage(ui)) },
        )
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(top, lp(MATCH, 0, 382f))
            addView(bottom, lp(MATCH, 0, 94f).margins(t = gap))
        }, flp(MATCH, MATCH))
    }

    private fun cardRow(gap: Int, vararg cards: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        cards.forEachIndexed { i, v -> addView(v, lp(0, MATCH, 1f).margins(l = if (i > 0) gap else 0)) }
    }

    private fun lighter(c: Int): Int = Color.rgb(
        Color.red(c) + (255 - Color.red(c)) / 4, Color.green(c) + (255 - Color.green(c)) / 4, Color.blue(c) + (255 - Color.blue(c)) / 4)

    private fun badge(res: Int, sizeUnits: Float, color: Int, tint: Int = Palette.TEXT): View = with(ctx) {
        FrameLayout(this).apply {
            background = gradient(lighter(color), color, u(sizeUnits * 0.24f).toFloat(), GradientDrawable.Orientation.TOP_BOTTOM)
            val i = u(sizeUnits * 0.56f)
            addView(icon(res, 20, tint), flp(i, i, Gravity.CENTER))
        }
    }

    private fun chevron(color: Int = Palette.TEXT_DIM) = ctx.icon(R.drawable.ic_chevron_right, 24, color)

    /** Sol panel: araç görseli, altında hız, yolculuk ve bakım. Yakıt yerine yolculuk: araçlar yakıtı çoğunlukla paylaşmıyor. */
    private fun carPanel(header: Boolean = true): View = with(ctx) {
        val r = u(20f).toFloat()
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pressable(rounded(CARD, r, 1, LINE), r)
            clipToOutline = true
            if (header) addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(u(20f), u(20f), u(16f), u(4f))
                addView(badge(R.drawable.ic_car, 60f, 0xFFC42A35.toInt()), lp(u(60f), u(60f)))
                addView(LinearLayout(this@with).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(t("Araç"), 26f, bold = true))
                    addView(text(t("Hız, yolculuk, bakım"), 19f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = u(4f)))
                }, lp(0, WRAP, 1f).margins(l = u(24f)))
                addView(chevron(Palette.TEXT), lp(u(30f), u(30f)))
            }, lp(MATCH, WRAP))
            addView(carPhoto(), lp(MATCH, 0, 1f))
            val stats = LinearLayout(this@with).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(u(20f), u(4f), u(16f), u(16f))
                speedValue = stat(this, R.drawable.ic_speed, t("Hız"))
                divider(this)
                tripValue = stat(this, R.drawable.ic_place, t("Yolculuk"))
                divider(this)
                careValue = stat(this, R.drawable.ic_build, t("Bakım"), withBar = true)
            }
            addView(stats, lp(MATCH, WRAP))
            onTap { ui.push(VehiclePage(ui)) }
        }
    }

    /**
     * Araç fotoğrafı: oranı korunur (782x465), araba kırpılmaz. Üstüne arabanın hattını izleyen yumuşak bir
     * gölge biner; duvar ve zemin kartın rengine erir, araba karanlıktan çıkıyormuş gibi görünür.
     */
    private fun carPhoto(): View = with(ctx) {
        val photo = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(CarPhoto.image.value)
            carImage = this
        }
        val shade = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageResource(R.drawable.home_car_shade)
        }
        val inner = FrameLayout(this).apply {
            addView(photo, flp(MATCH, MATCH))
            addView(shade, flp(MATCH, MATCH))
        }
        // Fotoğraf eldeki alana oranı bozulmadan sığdırılır (ne genişlikten ne yükseklikten taşar)
        object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                val w = measuredWidth
                val h = measuredHeight
                if (w <= 0 || h <= 0) return
                val ph = minOf(h, w * 465 / 782)
                val pw = ph * 782 / 465
                inner.measure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
            }
        }.apply {
            addView(inner, flp(WRAP, WRAP, Gravity.CENTER))
        }
    }

    private fun divider(row: LinearLayout) {
        row.addView(View(ctx).apply { setBackgroundColor(LINE) }, lp(1, MATCH))
    }

    private fun stat(row: LinearLayout, res: Int, name: String, withBar: Boolean = false): TextView = with(ctx) {
        val value = text("—", 26f, bold = true).apply { gravity = Gravity.CENTER }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(u(4f), 0, u(4f), 0)
            addView(icon(res, 20, Palette.TEXT_DIM), lp(u(34f), u(34f)))
            addView(text(name, 17f, Palette.TEXT_DIM), lp(WRAP, WRAP).margins(t = u(8f)))
            addView(value, lp(MATCH, WRAP).margins(t = u(10f)))
            val bar = ProgressBar(this@with, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressTintList = ColorStateList.valueOf(Palette.accent)
                progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                // Çubuğu olmayan sütunlarda da yazılar aynı hizada kalsın
                visibility = View.INVISIBLE
            }
            if (withBar) careBar = bar
            addView(bar, lp(u(90f), u(6f)).margins(t = u(8f)))
        }
        row.addView(box, lp(0, WRAP, 1f))
        value
    }

    /** "12,4 km" gibi: sayı büyük, birim küçük. */
    private fun number(value: String, unit: String): CharSequence = SpannableStringBuilder("$value $unit").apply {
        setSpan(AbsoluteSizeSpan(u(18f)), value.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun showSpeed() {
        val kmh = DriveState.speedKmh.value
        speedValue?.text = number(kmh?.let { "%.0f".format(tr, it) } ?: "—", "km/s")
    }

    private fun showTrip() {
        val km = DriveState.tripMeters.value / 1000f
        tripValue?.text = number("%.1f".format(tr, km), "km")
        showCare(km)
    }

    /** Yağ değişimi kayıtlıysa kalan km, değilse yaklaşan bakım tarihi; kayıt yoksa eklemeye çağırır. */
    private fun showCare(tripKm: Float) {
        val value = careValue ?: return
        val bar = careBar ?: return
        val odometer = VehicleData.odometerKm.value ?: CarLog.estimatedOdometer(tripKm)
        val left = CarLog.oilKmLeft(odometer)
        val due = Prefs.maintenanceDue().firstOrNull()?.second
        value.setTextColor(Palette.TEXT)
        bar.visibility = View.INVISIBLE
        when {
            left != null -> {
                if (left < 0) value.setTextColor(Palette.RED)
                value.text = number("%,d".format(tr, kotlin.math.abs(left).toInt()), if (left < 0) t("km geçti") else "km")
                bar.progress = (left / CarLog.oilIntervalKm * 100).toInt().coerceIn(0, 100)
                bar.visibility = View.VISIBLE
            }
            due != null && due < 0 -> { value.setTextColor(Palette.RED); value.text = t("Gecikti") }
            due != null -> value.text = number("$due", t("gün"))
            else -> { value.setTextColor(Palette.TEXT_DIM); value.text = t("Ekle") }
        }
    }

    private fun wideCard(title: String, subtitle: String, res: Int, color: Int, art: CardArt.Kind, onClick: () -> Unit): View = with(ctx) {
        val r = u(20f).toFloat()
        val pad = u(26f)
        val look = LayerDrawable(arrayOf(
            rounded(CARD, r),
            CardArt(art, color, resources.displayMetrics.density),
            rounded(0, r, 1, Palette.withAlpha(color, 0xA0)),
        ))
        FrameLayout(this).apply {
            background = pressable(look, r)
            clipToOutline = true
            addView(badge(res, 64f, color), flp(u(64f), u(64f)).margins(pad, u(18f)))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 32f, bold = true))
                addView(text(subtitle, 18f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(t = u(5f)))
            }, flp(MATCH, WRAP, Gravity.BOTTOM or Gravity.START).margins(l = pad + u(4f), b = u(18f), r = u(16f)))
            onTap(onClick)
        }
    }

    private fun weatherCard(): View = with(ctx) {
        val r = u(20f).toFloat()
        val w = WeatherHub.report.value
        val look = LayerDrawable(arrayOf(
            gradient(0xFF2B3647.toInt(), 0xFF10141C.toInt(), r, GradientDrawable.Orientation.TL_BR),
            CardArt(CardArt.Kind.WEATHER, 0xFF64748B.toInt(), resources.displayMetrics.density),
            rounded(0, r, 1, LINE),
        ))
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(look, r)
            clipToOutline = true
            setPadding(u(26f), 0, u(16f), 0)
            addView(text(w?.let { "${WeatherApi.emoji(it.code, it.isDay)} ${it.temp}°" } ?: t("Hava"), 50f, bold = true))
            addView(text(w?.let { it.place ?: WeatherApi.describe(it.code) } ?: t("Hava durumu"), 24f, bold = true),
                lp(MATCH, WRAP).margins(t = u(6f)))
            onTap { ui.push(WeatherPage(ui)) }
        }
    }

    private fun continueCard(): View = with(ctx) {
        val r = u(20f).toFloat()
        val play = FrameLayout(this).apply {
            background = rounded(Palette.withAlpha(Palette.GREEN, 0x30), u(38f).toFloat(), u(4f), Palette.GREEN)
            val i = icon(R.drawable.ic_play, 20)
            contPlay = i
            addView(i, flp(u(42f), u(42f), Gravity.CENTER))
            onTap(true) { resumeLast(ui) }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contTitle = text(t("Devam et"), 23f, bold = true).also { addView(it) }
            contSub = text("", 18f, Palette.TEXT_DIM).also { addView(it, lp(MATCH, WRAP).margins(t = u(5f))) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(play, lp(u(76f), u(76f)))
            addView(texts, lp(0, WRAP, 1f).margins(l = u(18f)))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        contBar = bar
        val times = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            contElapsed = text("", 17f, Palette.TEXT_DIM).also { addView(it, lp(0, WRAP, 1f)) }
            contTotal = text("", 17f, Palette.TEXT_DIM).also { addView(it) }
        }
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, lp(MATCH, u(7f)))
            addView(times, lp(MATCH, WRAP).margins(t = u(10f)))
        }
        contProgress = progress
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(rounded(CARD, r, 1, LINE), r)
            setPadding(u(24f), 0, u(20f), 0)
            addView(head, lp(MATCH, WRAP))
            addView(progress, lp(MATCH, WRAP).margins(t = u(22f)))
            onTap { openPlayer() }
        }
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Sadece gerçek süre bilinince (podcast) ilerleme çubuğu; canlı yayında gösterilmez. */
    private fun showContinue() {
        val title = contTitle ?: return
        val now = PlayerHub.now.value
        val playing = PlayerHub.playing.value
        val entry = now ?: Prefs.recents().firstOrNull()
        title.text = when {
            now != null && playing -> t("Şimdi çalıyor")
            else -> t("Devam et")
        }
        contSub?.text = entry?.title ?: t("TV veya radyo aç")
        contPlay?.setImageResource(if (now != null && playing) R.drawable.ic_pause else R.drawable.ic_play)
        val position = PlayerHub.position.value
        val duration = PlayerHub.duration.value
        val known = now != null && duration > 0
        contProgress?.visibility = if (known) View.VISIBLE else View.GONE
        if (known) {
            contBar?.progress = (position * 1000 / duration).toInt()
            contElapsed?.text = clock(position)
            contTotal?.text = clock(duration)
        }
    }

    private fun openPlayer() {
        if (PlayerHub.now.value?.kind == Kind.TV) ui.push(PlayerPage(ui)) else resumeLast(ui)
    }

    private fun shortcutCard(title: String, res: Int, color: Int, tint: Int = Palette.TEXT, onClick: () -> Unit): View = with(ctx) {
        val r = u(18f).toFloat()
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pressable(rounded(CARD, r, 1, LINE), r)
            setPadding(u(16f), 0, u(12f), 0)
            addView(badge(res, 56f, color, tint), lp(u(56f), u(56f)))
            addView(text(title, 23f, bold = true), lp(0, WRAP, 1f).margins(l = u(16f)))
            onTap(onClick)
        }
    }

    // ---- Bölünmüş dar ekran ----

    private fun compactList(): Unit = with(ctx) {
        speedValue = null; tripValue = null; careValue = null; careBar = null; contTitle = null
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        root.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(container, FrameLayout.LayoutParams(MATCH, WRAP))
        }, flp(MATCH, MATCH))
        val gap = dp(8)

        val bigCards = orderedCardDefs().map { d ->
            bigCard(d.title, d.icon, d.color, d.art, d.onClick)
        }
        addGrid(bigCards, 2, dp(110), gap)

        val shortcuts = ArrayList<Shortcut>()
        shortcuts += Shortcut(t("Favoriler"), t("Kanal ve radyolar"), R.drawable.ic_star, Palette.ORANGE) { ui.push(FavoritesPage(ui)) }
        Prefs.recents().firstOrNull()?.let { last ->
            shortcuts += Shortcut(t("Devam et"), last.title, R.drawable.ic_history, Palette.GREEN) {
                val list = Prefs.recents().filter { it.kind == last.kind }
                PlayerHub.play(list, 0)
                if (last.kind == Kind.TV) ui.push(PlayerPage(ui))
            }
        }
        shortcuts += Shortcut("Podcast", t("Dinle, kaldığın yerden"), R.drawable.ic_podcast, 0xFFE040FB.toInt()) { ui.push(PodcastPage(ui)) }
        shortcuts += Shortcut(t("Medya"), t("Telefondaki dosyalar"), R.drawable.ic_photo, 0xFF10B981.toInt()) { ui.push(LocalMediaPage(ui)) }
        val due = Prefs.maintenanceDue().firstOrNull { it.second <= 15 }
        shortcuts += Shortcut(t("Araç"), due?.let { (m, d) -> if (d < 0) t("{0} gecikti!", m.title) else t("{0}: {1} gün", m.title, d) }
            ?: t("Hız, yolculuk, bakım"), R.drawable.ic_speed, 0xFF16B98A.toInt(), warn = due != null) { ui.push(VehiclePage(ui)) }
        val w = WeatherHub.report.value
        shortcuts += Shortcut(w?.let { "${WeatherApi.emoji(it.code, it.isDay)} ${it.temp}°" } ?: t("Hava"),
            w?.let { it.place ?: WeatherApi.describe(it.code) } ?: t("Hava durumu"), R.drawable.ic_cloud, 0xFF7DB8FF.toInt()) {
            ui.push(WeatherPage(ui))
        }
        shortcuts += Shortcut(t("Tümü"), t("Tüm uygulamalar"), R.drawable.ic_apps, 0xFF94A3B8.toInt()) { ui.push(AppsPage(ui)) }

        val usable = lastWidth - container.paddingLeft - container.paddingRight
        val cols = (usable / dp(120)).coerceIn(2, 4)
        container.addView(View(this), lp(MATCH, gap))
        addGrid(shortcuts.map { smallCard(it) }, cols, dp(92), gap)
    }

    /** Görünümleri [perRow] sütunlu satırlara dizer; son satır eksikse boşlukla doldurulur. */
    private fun addGrid(views: List<View>, perRow: Int, rowHeight: Int, gap: Int) = with(ctx) {
        views.chunked(perRow).forEachIndexed { rowIndex, rowViews ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until perRow) {
                val v = rowViews.getOrNull(i) ?: View(this)
                row.addView(v, lp(0, MATCH, 1f).margins(r = if (i < perRow - 1) gap else 0))
            }
            container.addView(row, lp(MATCH, rowHeight).margins(t = if (rowIndex > 0) gap else 0))
        }
    }

    private fun bigCard(title: String, iconRes: Int, color: Int, art: CardArt.Kind, onClick: () -> Unit): View = with(ctx) {
        val r = dpf(18)
        val pad = dp(12)
        val look = LayerDrawable(arrayOf(
            rounded(CARD, r),
            CardArt(art, color, resources.displayMetrics.density),
            rounded(0, r, dp(1), Palette.withAlpha(color, 0x90)),
        ))
        FrameLayout(this).apply {
            background = pressable(look, r)
            clipToOutline = true
            val badge = FrameLayout(this@with).apply {
                background = rounded(color, dpf(14))
                addView(icon(iconRes, 22), flp(dp(22), dp(22), Gravity.CENTER))
            }
            addView(badge, flp(dp(40), dp(40)).margins(pad, pad))
            // Dar kartlarda başlık kesilmesin, sığacak kadar küçülsün
            addView(label(title, 22f, bold = true).apply {
                setAutoSizeTextTypeUniformWithConfiguration(12, 22, 1, TypedValue.COMPLEX_UNIT_DIP)
            }, flp(MATCH, dp(28), Gravity.BOTTOM or Gravity.START).margins(l = pad + dp(2), b = pad + dp(2), r = pad))
            onTap(onClick)
        }
    }

    private fun smallCard(s: Shortcut): View = with(ctx) {
        val r = dpf(14)
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = pressable(
                if (s.warn) rounded(Palette.withAlpha(Palette.ORANGE, 0x26), r, dp(1), Palette.withAlpha(Palette.ORANGE, 0xAA))
                else rounded(Palette.SURFACE, r, dp(1), 0x1AFFFFFF), r)
            val badge = FrameLayout(this@with).apply {
                background = rounded(Palette.withAlpha(s.color, 0x40), dpf(12))
                addView(icon(s.icon, 20, s.color), flp(dp(20), dp(20), Gravity.CENTER))
            }
            addView(badge, lp(dp(34), dp(34)))
            addView(label(s.title, 13f, bold = true).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP).margins(t = dp(6)))
            onTap(s.onClick)
        }
    }
}
