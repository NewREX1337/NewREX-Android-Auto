// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import coil.transform.RoundedCornersTransformation
import com.newrex.auto.DriveState
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Board
import com.newrex.auto.data.Kind
import com.newrex.auto.data.Prefs
import com.newrex.auto.WeatherHub
import com.newrex.auto.data.WeatherApi
import com.newrex.auto.pages.HomePage
import com.newrex.auto.pages.PlayerPage
import com.newrex.auto.pages.WeatherPage
import android.widget.ProgressBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Araç ekranındaki her sayfanın temeli. */
abstract class Page(val ui: CarUi) {
    val ctx: Context get() = ui.ctx
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    var view: View? = null

    open val title: String = ""
    /** Üst bar ve mini oynatıcı gizlenir (video oynatıcı gibi). */
    open val fullscreen: Boolean = false

    abstract fun build(): View
    /** Üst barın sağında gösterilecek sayfaya özel butonlar. */
    open fun actions(): List<View> = emptyList()
    /** true dönerse geri tuşu sayfa içinde kullanıldı demektir. */
    open fun onBack(): Boolean = false
    open fun onShow() {}
    open fun onDestroy() {}

    fun destroy() {
        onDestroy()
        scope.cancel()
    }
}

class CarUi(val ctx: Context, startOnApps: Boolean = false, private val onRebuild: (openSettings: Boolean) -> Unit) {
    val root = FrameLayout(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val stack = ArrayList<Page>()

    private val content = FrameLayout(ctx)
    private lateinit var topBar: LinearLayout
    private lateinit var backButton: View
    private lateinit var titleView: TextView
    private lateinit var actionsBox: LinearLayout
    private lateinit var driveChip: LinearLayout
    private lateinit var driveIcon: ImageView
    private lateinit var driveText: TextView
    private lateinit var clock: TextView
    private lateinit var mini: LinearLayout
    private lateinit var miniImage: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: FrameLayout
    private lateinit var miniFav: FrameLayout
    private lateinit var miniPrev: FrameLayout
    private lateinit var miniNext: FrameLayout
    private lateinit var miniProgress: ProgressBar
    private lateinit var weatherChip: TextView
    private var keyboard: View? = null
    private var toastView: TextView? = null

    // Sayfa 1: fotoğraflı karşılama ekranı, sayfa 2: uygulamalar (aşağıdaki sayfa yığını)
    private val wave = WaveBackground(ctx)
    private val pager = VerticalPager(ctx)
    private lateinit var board: BoardView
    private lateinit var aboutPage: Page
    private var surfaceVisible = true

    val pageCount get() = stack.size

    /** Uygulamalar (ya da altındaki Hakkında) sayfası mı açık, karşılama ekranı değil? */
    val onApps get() = pager.page >= 1

    /** Android Auto bölünmüş ekrandayken (sağda müzik/harita kartı) arayüz sadeleşir. */
    val compact = MutableStateFlow(false)

    private val tick = object : Runnable {
        override fun run() {
            clock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            handler.postDelayed(this, 15_000)
        }
    }

    init {
        Palette.load()
        Board.init(ctx)
        com.newrex.auto.data.CarLog.init(ctx)
        com.newrex.auto.data.FmPresets.init(ctx)
        val bg = Palette.safeBackground(Prefs.bgColor)
        root.setBackgroundColor(if (bg != 0) bg else Palette.BG_TOP)

        val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        column.addView(buildTopBar(), lp(MATCH, ctx.dp(60)))
        column.addView(content, lp(MATCH, 0, 1f))
        column.addView(buildMiniPlayer(), lp(MATCH, ctx.dp(66)).margins(ctx.dp(12), ctx.dp(4), ctx.dp(12), ctx.dp(10)))

        val accent2 = Palette.companion(Palette.accent)
        wave.setPalette(0, Palette.accent, accent2, animate = false, base = bg)
        wave.setPalette(1, Palette.accent, accent2, animate = false, base = bg)
        board = BoardView(this, scope).apply {
            onEditing = { refreshPagerLock() }
            onTheme = { c1, c2 -> wave.setPalette(0, c1, c2, base = bg) }
            onOpenApps = { pager.goTo(1) }
        }
        pager.addView(board, flp(MATCH, MATCH))
        pager.addView(column, flp(MATCH, MATCH))
        // Uygulamalar sayfasının en altından aşağı kaydırınca "Hakkında" gelir
        aboutPage = com.newrex.auto.pages.AboutPage(this)
        pager.addView(aboutPage.build().also { aboutPage.view = it }, flp(MATCH, MATCH))
        pager.onPosition = { wave.mix = it.coerceIn(0f, 1f) }
        pager.onPage = {
            if (it == 1) board.exitEdit()
            // Uygulamalar sayfasında arka plan daha seyrek çizilir (telefonu yormasın)
            wave.frameMs = if (it == 0) 40L else 66L
        }
        root.addView(wave, flp(MATCH, MATCH))
        root.addView(pager, flp(MATCH, MATCH))
        if (startOnApps) { pager.goTo(1, animate = false); wave.mix = 1f }
        // Telefondan ya da ayarlardan tema değişince arayüz yeni renklerle kurulur
        scope.launch {
            Prefs.theme.drop(1).collect { onRebuild(stack.any { it is com.newrex.auto.pages.SettingsPage }) }
        }

        scope.launch { DriveState.driving.collect { updateDriveChip(it) } }
        com.newrex.auto.SpeedAlert.init(ctx)
        scope.launch { com.newrex.auto.Saver.active.collect { wave.invalidate() } }
        scope.launch { DriveState.speedKmh.collect { checkSpeedLimit(it) } }
        scope.launch {
            combine(compact, WeatherHub.report) { c, w -> c to w }.collect { (c, w) ->
                titleView.visibility = if (c) View.INVISIBLE else View.VISIBLE
                driveText.visibility = if (c) View.GONE else View.VISIBLE
                weatherChip.visibility = if (c || w == null) View.GONE else View.VISIBLE
                if (w != null) weatherChip.text = "${WeatherApi.emoji(w.code, w.isDay)} ${w.temp}°"
            }
        }
        scope.launch {
            while (true) {
                WeatherHub.refresh(ctx)
                delay(30 * 60_000L)
            }
        }
        scope.launch {
            combine(PlayerHub.position, PlayerHub.duration) { p, d -> p to d }.collect { (p, d) ->
                miniProgress.max = 1000
                miniProgress.progress = if (d > 0) (p * 1000 / d).toInt() else 0
            }
        }
        handler.postDelayed({ showStartupAlerts() }, 2500)
        scope.launch {
            combine(PlayerHub.now, PlayerHub.playing, Prefs.changes) { e, p, _ -> e to p }
                .collect { refreshMini() }
        }
        handler.post(tick)
        push(HomePage(this))
    }

    // ---- Sayfa yönetimi ----

    fun push(page: Page) {
        stack.lastOrNull()?.view?.visibility = View.GONE
        val v = page.build()
        page.view = v
        content.addView(v, flp(MATCH, MATCH))
        stack.add(page)
        refreshChrome()
        page.onShow()
        if (stack.size > 1 && pager.page != 1) pager.goTo(1, animate = false)
    }

    fun pop() {
        if (stack.size <= 1) return
        val page = stack.removeAt(stack.lastIndex)
        page.destroy()
        content.removeView(page.view)
        stack.last().view?.visibility = View.VISIBLE
        refreshChrome()
        stack.last().onShow()
    }

    /** Android Auto'daki ev tuşu: alt sayfalardan uygulamalara, uygulamalardan karşılama ekranına. */
    fun home() {
        hideKeyboard()
        if (stack.size > 1) while (stack.size > 1) pop()
        else pager.goTo(if (pager.page == 1) 0 else 1)
    }

    fun back() {
        if (keyboard != null) { hideKeyboard(); return }
        if (!stack.last().onBack()) pop()
    }

    /** Sayfanın üst bar butonları değiştiğinde çağrılır. */
    fun refreshChrome() {
        val top = stack.last()
        topBar.visibility = if (top.fullscreen) View.GONE else View.VISIBLE
        backButton.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        titleView.text = top.title
        actionsBox.removeAllViews()
        top.actions().forEach { actionsBox.addView(it, lp(ctx.dp(44), ctx.dp(44)).margins(l = ctx.dp(8))) }
        refreshMini()
        refreshPagerLock()
        // Animasyon sadece ana sayfalarda; alt sayfalarda ve videoda durur, telefonu yormaz
        wave.running = surfaceVisible && stack.size == 1
    }

    /** Araç ekranı başka bir şey gösterirken (geri görüş kamerası vb.) animasyon boşuna çizilmesin. */
    fun setSurfaceVisible(visible: Boolean) {
        surfaceVisible = visible
        wave.running = visible && stack.size == 1
    }

    private fun refreshPagerLock() {
        pager.locked = stack.size > 1 || keyboard != null || (::board.isInitialized && board.editing)
    }


    fun destroy() {
        wave.running = false
        handler.removeCallbacksAndMessages(null)
        stack.forEach { it.destroy() }
        stack.clear()
        if (::aboutPage.isInitialized) aboutPage.destroy()
        scope.cancel()
    }

    /** Android Auto'nun sağ üst köşedeki kendi butonu için boş bırakılması gereken genişlik. */
    var topRightReserve = 0
        private set

    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, topRightReserve: Int = 0) {
        this.topRightReserve = topRightReserve
        root.setPadding(left, top, right, bottom)
        topBar.setPadding(ctx.dp(14), ctx.dp(8), maxOf(ctx.dp(14), topRightReserve), 0)
        board.setTopRightReserve(topRightReserve)
        val visibleWidth = ctx.resources.displayMetrics.widthPixels - left - right
        compact.value = visibleWidth < ctx.dp(600)
    }

    private var overLimit = false
    private var lastSpeedAlert = 0L
    private var speedBanner: TextView? = null

    /** Şeride dokunulunca bu aşımda şerit ve ses kapanır; hız sınırın altına inince yeniden kurulur. */
    private var speedMuted = false

    /**
     * Hız uyarısı: sınır geçilince sesli uyarır ve ekranın üstünde büyük kırmızı bir şerit çıkar. Şerit hız
     * sınırın altına inene kadar kalır; sınırın üstünde kalınırsa ses 45 sn'de bir tekrarlanır.
     */
    private fun checkSpeedLimit(kmh: Float?) {
        val limit = com.newrex.auto.data.CarLog.speedLimit
        if (limit <= 0 || kmh == null) { overLimit = false; showSpeedBanner(null, limit); return }
        val now = System.currentTimeMillis()
        if (kmh > limit + 0.5f) {
            if (!speedMuted && ((!overLimit && now - lastSpeedAlert > 20_000) || now - lastSpeedAlert > 45_000)) {
                lastSpeedAlert = now
                com.newrex.auto.SpeedAlert.play(ctx, kmh.toInt())
            }
            overLimit = true
        } else if (kmh < limit - 3) { overLimit = false; speedMuted = false }
        showSpeedBanner(if (overLimit && !speedMuted) kmh.toInt() else null, limit)
    }

    private fun showSpeedBanner(kmh: Int?, limit: Int) {
        if (kmh == null) {
            speedBanner?.let { root.removeView(it) }
            speedBanner = null
            return
        }
        val b = speedBanner ?: ctx.label("", 26f, bold = true).apply {
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(ctx.dp(28), ctx.dp(12), ctx.dp(28), ctx.dp(12))
            background = rounded(0xF2D32F2F.toInt(), ctx.dpf(18), ctx.dp(2), android.graphics.Color.WHITE)
            elevation = ctx.dpf(12)
            setOnClickListener { speedMuted = true; showSpeedBanner(null, limit) }
            speedBanner = this
            root.addView(this, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(t = ctx.dp(64)))
        }
        b.text = "⚠️  $kmh km/s  •  sınır $limit   ✕"
    }

    /** Oturum başında bir kez: yaklaşan bakım ve yağmur uyarısı. */
    private fun showStartupAlerts() {
        if (!SessionAlerts.locationShown && !com.newrex.auto.DriveState.hasLocationPermission(ctx)) {
            SessionAlerts.locationShown = true
            toast("📍 Hız, yolculuk ve park modu için telefonda NewREX'e konum izni ver.", long = true)
            return
        }
        if (!SessionAlerts.maintenanceShown) {
            SessionAlerts.maintenanceShown = true
            Prefs.maintenanceDue().firstOrNull { it.second <= 15 }?.let { (m, days) ->
                toast(when {
                    days < 0 -> "${m.emoji} ${m.title} ${-days} gün gecikti!"
                    days == 0L -> "${m.emoji} ${m.title} bugün!"
                    else -> "${m.emoji} ${m.title} için $days gün kaldı"
                }, long = true)
            }
        }
        if (!SessionAlerts.oilShown) {
            SessionAlerts.oilShown = true
            val km = com.newrex.auto.VehicleData.odometerKm.value ?: com.newrex.auto.data.CarLog.estimatedOdometer()
            com.newrex.auto.data.CarLog.oilKmLeft(km)?.takeIf { it <= 500 }?.let { left ->
                handler.postDelayed({
                    toast(if (left < 0) "🛢️ Yağ değişimi ${(-left).toInt()} km gecikti!" else "🛢️ Yağ değişimine ${left.toInt()} km kaldı", long = true)
                }, 6500)
            }
        }
        scope.launch {
            delay(7000)
            val w = WeatherHub.report.value ?: return@launch
            if (!SessionAlerts.rainShown && w.rainSoon >= 60) {
                SessionAlerts.rainShown = true
                toast("🌧️ Önümüzdeki saatlerde yağmur bekleniyor (%${w.rainSoon}). Dikkatli sür!", long = true)
            }
        }
    }

    // ---- Klavye ve bildirim ----

    fun showKeyboard(
        hint: String,
        initial: String = "",
        secret: Boolean = false,
        doneLabel: String = "Ara",
        allowEmpty: Boolean = false,
        onDone: (String) -> Unit,
    ) {
        if (DriveState.driving.value) {
            toast("Sürüş sırasında yazı yazılamaz. Lütfen park halindeyken deneyin.")
            return
        }
        hideKeyboard()
        val kb = KeyboardView(ctx, hint, initial, secret, doneLabel,
            onDone = { text ->
                hideKeyboard()
                val value = if (secret) text else text.trim()
                if (value.isNotEmpty() || allowEmpty) onDone(value)
            },
            onClose = { hideKeyboard() })
        keyboard = kb
        root.addView(kb, flp(MATCH, MATCH))
        refreshPagerLock()
    }

    fun hideKeyboard() {
        keyboard?.let { root.removeView(it) }
        keyboard = null
        refreshPagerLock()
    }

    fun toast(message: String, long: Boolean = false) {
        toastView?.let { root.removeView(it) }
        val t = ctx.label(message, 15f, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(20), ctx.dp(12), ctx.dp(20), ctx.dp(12))
            background = rounded(0xEE222639.toInt(), ctx.dpf(14))
            neonGlow(true)
        }
        toastView = t
        root.addView(t, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = ctx.dp(90)))
        handler.postDelayed({ if (toastView === t) { root.removeView(t); toastView = null } }, if (long) 6000 else 3000)
    }

    // ---- Üst bar ----

    private fun buildTopBar(): View = with(ctx) {
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(14), 0)
        }
        backButton = circleButton(R.drawable.ic_back, 44) { back() }
        topBar.addView(backButton, lp(dp(44), dp(44)).margins(r = dp(10)))

        val logo = SpannableStringBuilder("NewREX").apply {
            setSpan(ForegroundColorSpan(Palette.accent), 3, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        topBar.addView(label(logo, 22f, bold = true).apply { neonText(8) })
        titleView = label("", 17f, Palette.TEXT_DIM)
        topBar.addView(titleView, lp(0, WRAP, 1f).margins(l = dp(14)))

        actionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(actionsBox)

        weatherChip = label("", 15f, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), 0, dp(10), 0)
            val r = dpf(16)
            background = pressable(rounded(Palette.SURFACE_STRONG, r), r)
            visibility = View.GONE
            onTap { if (stack.last() !is WeatherPage) push(WeatherPage(this@CarUi)) }
        }
        topBar.addView(weatherChip, lp(WRAP, dp(32)).margins(l = dp(10)))

        driveIcon = icon(R.drawable.ic_parking, 18)
        driveText = label("Park", 13f, bold = true)
        driveChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(12), 0)
            addView(driveIcon, lp(dp(18), dp(18)).margins(r = dp(6)))
            addView(driveText)
        }
        topBar.addView(driveChip, lp(WRAP, dp(32)).margins(l = dp(12)))

        clock = label("", 20f, bold = true)
        topBar.addView(clock, lp(WRAP, WRAP).margins(l = dp(12)))
        topBar
    }

    private fun updateDriveChip(driving: Boolean) {
        val color = if (driving) Palette.ORANGE else Palette.GREEN
        driveChip.background = rounded(Palette.withAlpha(color, 0x40), ctx.dpf(16))
        driveIcon.setImageResource(if (driving) R.drawable.ic_car else R.drawable.ic_parking)
        driveText.text = if (driving) "Sürüşte" else "Park"
    }

    // ---- Mini oynatıcı ----

    private fun buildMiniPlayer(): View = with(ctx) {
        miniImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        miniTitle = label("", 16f, bold = true)
        miniSub = label("", 12f, Palette.TEXT_DIM)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(miniTitle)
            addView(miniSub, lp(WRAP, WRAP).margins(t = dp(4)))
        }
        miniFav = circleButton(R.drawable.ic_star_border, 44, bg = 0) {
            PlayerHub.now.value?.let { Prefs.toggleFavorite(it) }
        }
        miniPlay = circleButton(R.drawable.ic_play, 48, bg = Palette.accent) { PlayerHub.toggle() }

        mini = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
            background = pressable(rounded(0xFF1D2033.toInt(), dpf(18)), dpf(18))
            addView(miniImage, lp(dp(46), dp(46)).margins(r = dp(12)))
            addView(texts, lp(0, WRAP, 1f))
            addView(miniFav, lp(dp(44), dp(44)).margins(l = dp(6)))
            miniPrev = circleButton(R.drawable.ic_prev, 44, bg = 0) { PlayerHub.prev() }
            miniNext = circleButton(R.drawable.ic_next, 44, bg = 0) { PlayerHub.next() }
            addView(miniPrev, lp(dp(44), dp(44)).margins(l = dp(6)))
            addView(miniPlay, lp(dp(48), dp(48)).margins(l = dp(6)))
            addView(miniNext, lp(dp(44), dp(44)).margins(l = dp(6)))
            addView(circleButton(R.drawable.ic_close, 44, bg = 0, tint = Palette.TEXT_DIM) { PlayerHub.stop() },
                lp(dp(44), dp(44)).margins(l = dp(6)))
            // TV sesi çalarken çubuğa dokununca görüntüye geri dön
            onTap {
                if (PlayerHub.now.value?.kind == Kind.TV && stack.last() !is PlayerPage) push(PlayerPage(this@CarUi))
            }
        }
        miniProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x22FFFFFF)
        }
        FrameLayout(this).apply {
            addView(mini, flp(MATCH, MATCH))
            addView(miniProgress, flp(MATCH, dp(3), Gravity.BOTTOM).margins(dp(18), 0, dp(18), dp(1)))
        }
    }

    private fun refreshMini() {
        if (!::mini.isInitialized || stack.isEmpty()) return
        val e = PlayerHub.now.value
        val top = stack.last()
        val hide = e == null || top.fullscreen || top is PlayerPage
        (mini.parent as View).visibility = if (hide) View.GONE else View.VISIBLE
        miniProgress.visibility = if (e?.kind == Kind.PODCAST) View.VISIBLE else View.GONE
        if (e == null) return
        miniTitle.text = e.title
        miniSub.text = when (e.kind) {
            Kind.TV -> "Canlı TV • görüntü için dokun"
            Kind.RADIO -> "Radyo" + if (e.group.isNotBlank()) " • ${e.group}" else ""
            Kind.PODCAST -> "Podcast" + if (e.group.isNotBlank()) " • ${e.group}" else ""
        }
        val pod = e.kind == Kind.PODCAST
        (miniPrev.getChildAt(0) as ImageView).setImageResource(if (pod) R.drawable.ic_replay else R.drawable.ic_prev)
        (miniNext.getChildAt(0) as ImageView).setImageResource(if (pod) R.drawable.ic_forward else R.drawable.ic_next)
        val fallback = if (e.kind == Kind.RADIO) LetterArt(e.title, ctx.resources.displayMetrics.density)
        else androidx.core.content.ContextCompat.getDrawable(ctx, kindIcon(e.kind))
        miniImage.load(com.newrex.auto.data.Logos.best(e)) {
            placeholder(fallback); error(fallback); fallback(fallback)
            transformations(RoundedCornersTransformation(ctx.dpf(8)))
        }
        (miniPlay.getChildAt(0) as ImageView).setImageResource(
            if (PlayerHub.playing.value) R.drawable.ic_pause else R.drawable.ic_play
        )
        val fav = Prefs.isFavorite(e)
        (miniFav.getChildAt(0) as ImageView).apply {
            setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
            imageTintList = android.content.res.ColorStateList.valueOf(if (fav) Palette.ORANGE else Palette.TEXT)
        }
    }
}

/** Oturum (araç bağlantısı) başına bir kez gösterilen uyarılar. */
object SessionAlerts {
    var maintenanceShown = false
    var rainShown = false
    var locationShown = false
    var oilShown = false
    fun reset() { maintenanceShown = false; rainShown = false; locationShown = false; oilShown = false }
}

fun kindIcon(kind: Kind) = when (kind) {
    Kind.TV -> R.drawable.ic_live_tv
    Kind.RADIO -> R.drawable.ic_radio
    Kind.PODCAST -> R.drawable.ic_podcast
}

/** Sürüş sırasında video/web görüntüsünü kapatan katman. Ses devam eder. */
fun Page.parkGuard(): View = with(ctx) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(0xFA080A12.toInt())
        isClickable = true
        addView(icon(R.drawable.ic_car, 64, Palette.ORANGE), lp(dp(64), dp(64)))
        addView(label("Sürüş halindesin", 24f, bold = true).apply { gravity = Gravity.CENTER },
            lp(WRAP, WRAP).margins(t = dp(14)))
        addView(label("Güvenliğin için görüntü gizlendi, ses devam ediyor.\nAraç durunca görüntü otomatik geri gelir.",
            15f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER },
            lp(WRAP, WRAP).margins(t = dp(8)))
        visibility = View.GONE
    }
    scope.launch {
        combine(DriveState.driving, Prefs.changes) { driving, _ -> driving && Prefs.parkModeVideo }
            .distinctUntilChanged()
            .collect { block -> box.visibility = if (block) View.VISIBLE else View.GONE }
    }
    box
}
