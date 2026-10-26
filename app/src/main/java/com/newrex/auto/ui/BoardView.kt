// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.load
import com.newrex.auto.DriveState
import com.newrex.auto.PhotoPickActivity
import com.newrex.auto.R
import com.newrex.auto.data.Board
import com.newrex.auto.data.BoardItem
import com.newrex.auto.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Araçta açılışta gelen karşılama ekranı: telefondan seçilen fotoğraflar ve saat. Öğeler "Düzenle" ile
 * taşınabilir, büyütülüp küçültülebilir ya da kaldırılabilir; sağ üstteki + ile geri eklenir.
 */
class BoardView(private val ui: CarUi, private val scope: CoroutineScope) : FrameLayout(ui.ctx) {
    private val ctx = ui.ctx
    private val handler = Handler(Looper.getMainLooper())
    private val tr = Locale("tr", "TR")
    private val layer = Layer()
    private val holders = LinkedHashMap<String, Holder>()
    private val buttons = LinearLayout(ctx)
    private lateinit var editButton: FrameLayout
    private val bottomHint: View
    private val editHint: TextView
    private var menu: View? = null
    private var introPlayed = false

    var editing = false
        private set
    private var selected: String? = null

    /** Düzenleme modu açılıp kapanınca (sayfa kaydırmayı kilitlemek için). */
    var onEditing: ((Boolean) -> Unit)? = null
    /** Ekranın renkleri fotoğrafa göre değişince: (renk1, renk2). */
    var onTheme: ((Int, Int) -> Unit)? = null
    var onOpenApps: (() -> Unit)? = null

    init {
        addView(layer, flp(MATCH, MATCH))

        buttons.orientation = LinearLayout.HORIZONTAL
        editButton = ctx.circleButton(R.drawable.ic_edit, 48, bg = 0x33FFFFFF) { toggleEdit() }
        buttons.addView(editButton, lp(ctx.dp(48), ctx.dp(48)))
        buttons.addView(ctx.circleButton(R.drawable.ic_add, 48, bg = 0x33FFFFFF) { showMenu() },
            lp(ctx.dp(48), ctx.dp(48)).margins(l = ctx.dp(10)))
        addView(buttons, flp(WRAP, WRAP, Gravity.TOP or Gravity.END).margins(t = ctx.dp(14), r = ctx.dp(16)))

        bottomHint = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(18), ctx.dp(6), ctx.dp(18), ctx.dp(8))
            val r = ctx.dpf(20)
            background = pressable(rounded(0, r), r)
            val arrow = ctx.icon(R.drawable.ic_up, 26, Palette.TEXT_DIM)
            addView(arrow, lp(ctx.dp(26), ctx.dp(26)))
            addView(ctx.label("Uygulamalar", 13f, Palette.TEXT_DIM), lp(WRAP, WRAP))
            // Yukarı kaydırılabileceğini belli eden hafif zıplama. Sonsuz döngü bütün araç ekranını saniyede 60 kez
            // yeniden çizdiriyordu (ölçüldü); 3 kez zıplayıp durur.
            if (!com.newrex.auto.Saver.on) ObjectAnimator.ofFloat(arrow, View.TRANSLATION_Y, 0f, -ctx.dpf(5), 0f).apply {
                duration = 1800
                repeatCount = 2
                startDelay = 800
                start()
            }
            onTap { onOpenApps?.invoke() }
        }
        addView(bottomHint, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = ctx.dp(8)))

        editHint = ctx.label("Bir öğeye dokun, sonra sürükleyerek taşı.  ⤢ boyut  ✕ kaldır", 14f, Palette.TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(8))
            background = rounded(0x66000000, ctx.dpf(16))
            visibility = View.GONE
        }
        addView(editHint, flp(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).margins(b = ctx.dp(16)))

        scope.launch {
            combine(Board.items, Board.photos) { i, _ -> i }.collect { sync(it) }
        }
        // Telefondan saat tasarımı değişince saat yeniden kurulur
        scope.launch {
            Prefs.looks.drop(1).collect {
                holders.values.filter { it.item.type == BoardItem.CLOCK }.forEach { layer.removeView(it.view); holders.remove(it.item.id) }
                sync(Board.items.value)
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    /** Android Auto'nun sağ üst köşedeki butonu için boşluk. */
    fun setTopRightReserve(px: Int) {
        (buttons.layoutParams as LayoutParams).rightMargin = maxOf(ctx.dp(16), px)
        buttons.requestLayout()
    }

    fun exitEdit() { if (editing) toggleEdit() }

    // ---- Öğeler ----

    private fun sync(items: List<BoardItem>) {
        val ids = items.map { it.id }.toSet()
        holders.keys.filter { it !in ids }.forEach { layer.removeView(holders.remove(it)!!.view) }
        for (item in items) {
            val h = holders[item.id]
            if (h == null || h.item.size != item.size) {
                h?.let { layer.removeView(it.view) }
                val nh = Holder(item)
                holders[item.id] = nh
                layer.addView(nh.view, LayoutParams(WRAP, WRAP))
            } else h.item = item
        }
        if (selected != null && selected !in ids) selected = null
        refreshDecor()
        layer.requestLayout()

        val theme = Board.themePhoto()
        if (theme != null) onTheme?.invoke(theme.c1, theme.c2)
        else onTheme?.invoke(Palette.accent, Palette.companion(Palette.accent))

        if (!introPlayed && items.isNotEmpty()) {
            introPlayed = true
            holders.values.forEachIndexed { i, h ->
                h.view.alpha = 0f
                h.view.scaleX = 0.92f; h.view.scaleY = 0.92f
                h.view.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(500L + i * 140L).setDuration(700).start()
            }
        }
    }

    /** Ekran boyutuna göre ölçek (geniş ekranlarda büyür, bölünmüş ekranda küçülür). */
    private fun scale(): Float {
        val w = layer.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels
        val h = layer.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels
        return minOf(w / ctx.dpf(980), h / ctx.dpf(540)).coerceIn(0.5f, 1.3f)
    }

    private inner class Holder(var item: BoardItem) {
        val view = FrameLayout(ctx)
        private val content: View
        private val removeBadge: View
        private val sizeBadge: View
        private var wiggle: ObjectAnimator? = null
        private var photoW = WRAP
        private var photoH = WRAP

        init {
            val s = scale()
            content = if (item.type == BoardItem.CLOCK) buildClock(s) else buildPhoto(s)
            view.addView(content, flp(photoW, photoH, Gravity.CENTER).margins(ctx.dp(14), ctx.dp(14), ctx.dp(14), ctx.dp(14)))
            removeBadge = ctx.circleButton(R.drawable.ic_close, 34, bg = 0xEE2A2D3E.toInt()) { Board.remove(item.id) }
            view.addView(removeBadge, flp(ctx.dp(34), ctx.dp(34), Gravity.TOP or Gravity.START))
            sizeBadge = ctx.circleButton(R.drawable.ic_resize, 34, bg = 0xEE2A2D3E.toInt()) {
                Board.update(item.copy(size = (item.size + 1) % 3))
            }
            view.addView(sizeBadge, flp(ctx.dp(34), ctx.dp(34), Gravity.BOTTOM or Gravity.END))
        }

        private fun buildClock(s: Float): View {
            val (w, h) = ClockView.sizeFor(Prefs.clockStyle, item.size, s, ctx.resources.displayMetrics.density)
            // Ekrandan taşmasın
            val maxW = (layer.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) * 0.8f
            val maxH = (layer.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels) * 0.82f
            val fit = minOf(1f, maxW / w, maxH / h)
            photoW = (w * fit).toInt(); photoH = (h * fit).toInt()
            return ClockView(ctx, Prefs.clockStyle)
        }

        private fun buildPhoto(s: Float): View {
            val photo = Board.photo(item.file)
            val aspect = photo?.aspect ?: 1f
            val long = ctx.dpf(intArrayOf(190, 270, 360)[item.size]) * s
            var w = if (aspect >= 1f) long else long * aspect
            var h = if (aspect >= 1f) long / aspect else long
            // Ekrandan taşmasın; oran korunarak küçülür, fotoğraf asla esnemez
            val maxW = (layer.width.takeIf { it > 0 } ?: ctx.resources.displayMetrics.widthPixels) * 0.8f
            val maxH = (layer.height.takeIf { it > 0 } ?: ctx.resources.displayMetrics.heightPixels) * 0.82f
            val fit = minOf(1f, maxW / w, maxH / h)
            w *= fit; h *= fit
            val radius = ctx.dpf(22) * s
            photoW = w.toInt(); photoH = h.toInt()
            return ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, radius)
                }
                clipToOutline = true
                setBackgroundColor(0x22FFFFFF)
                item.file?.let { load(Board.file(it)) { crossfade(400); size(photoW, photoH) } }
            }
        }

        fun decorate() {
            val sel = editing && selected == item.id
            removeBadge.visibility = if (editing) View.VISIBLE else View.GONE
            sizeBadge.visibility = if (editing) View.VISIBLE else View.GONE
            val r = ctx.dpf(26)
            view.background = when {
                sel -> GradientDrawable().apply { cornerRadius = r; setColor(Palette.withAlpha(Palette.accent, 0x22)); setStroke(ctx.dp(2), Palette.accent) }
                editing -> GradientDrawable().apply { cornerRadius = r; setStroke(ctx.dp(1), 0x55FFFFFF, ctx.dpf(8), ctx.dpf(6)) }
                else -> null
            }
            if (editing && wiggle == null) {
                wiggle = ObjectAnimator.ofFloat(view, View.ROTATION, -0.8f, 0.8f).apply {
                    duration = 260L + (item.id.hashCode() and 0x3F)
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            } else if (!editing) {
                wiggle?.cancel(); wiggle = null
                view.rotation = 0f
            }
        }
    }

    private fun refreshDecor() = holders.values.forEach { it.decorate() }

    private fun toggleEdit() {
        if (!editing && DriveState.driving.value) {
            ui.toast("Düzenleme park halindeyken yapılabilir.")
            return
        }
        editing = !editing
        if (!editing) selected = null
        (editButton.getChildAt(0) as ImageView).setImageResource(if (editing) R.drawable.ic_check else R.drawable.ic_edit)
        editButton.background = rounded(if (editing) Palette.accent else 0x33FFFFFF, ctx.dpf(24))
        bottomHint.visibility = if (editing) View.GONE else View.VISIBLE
        editHint.visibility = if (editing) View.VISIBLE else View.GONE
        refreshDecor()
        onEditing?.invoke(editing)
    }

    // ---- Ekle menüsü ----

    private fun showMenu() {
        closeMenu()
        val panel = ctx.panel().apply {
            background = rounded(0xF2161A2B.toInt(), ctx.dpf(24))
            setPadding(ctx.dp(20), ctx.dp(16), ctx.dp(20), ctx.dp(18))
            isClickable = true
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ctx.label("Ekrana ekle", 20f, bold = true), lp(0, WRAP, 1f))
            addView(ctx.circleButton(R.drawable.ic_close, 40) { closeMenu() }, lp(ctx.dp(40), ctx.dp(40)))
        }
        panel.addView(head, lp(MATCH, WRAP))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val tile = ctx.dp(116)
        fun addTile(v: View, action: () -> Unit) {
            v.onTap { closeMenu(); action() }
            row.addView(v, lp(tile, tile).margins(r = ctx.dp(12)))
        }
        addTile(textTile(R.drawable.ic_add, "Telefondan\nseç", Palette.accent)) {
            if (DriveState.driving.value) { ui.toast("Fotoğrafı park halindeyken seçebilirsin."); return@addTile }
            PhotoPickActivity.start(ctx.applicationContext)
            ui.toast("Telefonunda fotoğraf seçme ekranı açıldı.", long = true)
        }
        if (!Board.hasClock()) addTile(textTile(null, "Saat", 0x33FFFFFF, big = SimpleDateFormat("HH:mm", tr).format(Date()))) {
            Board.addClock()
        }
        val onBoard = Board.items.value.mapNotNull { it.file }.toSet()
        Board.photos.value.filter { it.file !in onBoard }.forEach { p ->
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                val r = ctx.dpf(18)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, r)
                }
                clipToOutline = true
                load(Board.file(p.file)) { size(tile, tile) }
            }
            addTile(iv) { Board.addPhoto(p.file) }
        }
        panel.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, lp(MATCH, WRAP).margins(t = ctx.dp(14)))
        panel.addView(ctx.label(
            if (onBoard.size < Board.photos.value.size || !Board.hasClock()) "Kaldırdığın öğeler burada durur, dokununca geri gelir."
            else "Fotoğraflar sadece bu telefonda saklanır, hiçbir yere gönderilmez.",
            13f, Palette.TEXT_FAINT, lines = 2), lp(MATCH, WRAP).margins(t = ctx.dp(12)))

        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { closeMenu() }
            addView(panel, flp(minOf(ctx.dp(640), (this@BoardView.width - ctx.dp(40)).coerceAtLeast(ctx.dp(300))), WRAP, Gravity.CENTER))
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
        }
        menu = overlay
        addView(overlay, flp(MATCH, MATCH))
    }

    private fun textTile(icon: Int?, text: String, bg: Int, big: String? = null): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        val r = ctx.dpf(18)
        background = pressable(rounded(bg, r), r)
        if (icon != null) addView(ctx.icon(icon, 34), lp(ctx.dp(34), ctx.dp(34)))
        if (big != null) addView(ctx.label(big, 28f).apply { typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL) })
        addView(ctx.label(text, 14f, bold = true, lines = 2).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(t = ctx.dp(6)))
    }

    private fun closeMenu() {
        menu?.let { removeView(it) }
        menu = null
    }

    // ---- Yerleşim ve sürükleme ----

    /** Öğeleri kayıtlı konumlarına yerleştirir; düzenleme modunda dokunmayla taşır. */
    private inner class Layer : FrameLayout(ctx) {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var lastX = 0f
        private var lastY = 0f
        private var downX = 0f
        private var downY = 0f
        private var moving = false
        private var lastW = 0
        private var lastH = 0

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            // Ekran boyutu değişince (bölünmüş ekran) öğeler yeni ölçekle yeniden kurulur
            if ((width != lastW || height != lastH) && lastW != 0) {
                lastW = width; lastH = height
                post { val items = Board.items.value; holders.values.forEach { removeView(it.view) }; holders.clear(); sync(items) }
                return
            }
            lastW = width; lastH = height
            holders.values.forEach { place(it.view, it.item.cx, it.item.cy) }
        }

        fun place(v: View, cx: Float, cy: Float) {
            val m = ctx.dp(6).toFloat()
            v.translationX = (cx * width - v.width / 2f).coerceIn(m, (width - v.width - m).coerceAtLeast(m))
            v.translationY = (cy * height - v.height / 2f).coerceIn(m, (height - v.height - m).coerceAtLeast(m))
        }

        private fun hit(x: Float, y: Float): Holder? = holders.values.reversed().firstOrNull {
            val v = it.view
            x >= v.translationX && x < v.translationX + v.width && y >= v.translationY && y < v.translationY + v.height
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!editing) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                    moving = false
                    // Araç ekranında kaydırma hep ekranın ortasından başlar; boşluğa denk gelirse seçili öğe taşınır
                    hit(ev.x, ev.y)?.let { if (selected != it.item.id) { selected = it.item.id; refreshDecor() } }
                }
                MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) moving = true
            }
            return moving
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!editing) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y
                    hit(ev.x, ev.y)?.let { selected = it.item.id } ?: run { if (selected == null) selected = holders.keys.firstOrNull() }
                    refreshDecor()
                }
                MotionEvent.ACTION_MOVE -> {
                    val h = holders[selected] ?: return true
                    h.view.translationX += ev.x - lastX
                    h.view.translationY += ev.y - lastY
                    lastX = ev.x; lastY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val h = holders[selected] ?: return true
                    if (width > 0 && height > 0) {
                        val cx = ((h.view.translationX + h.view.width / 2f) / width).coerceIn(0f, 1f)
                        val cy = ((h.view.translationY + h.view.height / 2f) / height).coerceIn(0f, 1f)
                        place(h.view, cx, cy)
                        Board.update(h.item.copy(cx = cx, cy = cy))
                    }
                    moving = false
                }
            }
            return true
        }
    }

}
