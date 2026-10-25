// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.FmPreset
import com.newrex.auto.data.FmPresets
import com.newrex.auto.data.Kind
import com.newrex.auto.data.MediaEntry
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.FmDisplayView
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.pill
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Klasik FM radyo görünümü: frekans göstergesi (telefondan 8 tasarımdan biri seçilir), kaydırılan skala, ileri/geri istasyon arama.
 * Frekanslara internet radyoları yerleştirilir; hazır olanlar İstanbul frekanslarıdır, ☆ ile her frekansa
 * istenen istasyon kaydedilebilir.
 */
class FmTunerPage(ui: CarUi) : Page(ui) {
    override val title = "FM"

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nixie: FmDisplayView
    private lateinit var dial: DialView
    private lateinit var stationName: TextView
    private lateinit var stationInfo: TextView
    private lateinit var saveButton: View
    private lateinit var cities: LinearLayout
    private var freq = FmPresets.lastFreq
    private val tuneLater = Runnable { tuneNow() }

    override fun build(): View = with(ctx) {
        nixie = FmDisplayView(this, Prefs.fmStyle)
        dial = DialView(this)
        dial.pointer = nixie.pointerColor
        stationName = label("", 22f, bold = true).apply { gravity = Gravity.CENTER }
        stationInfo = label("", 13f, Palette.TEXT_DIM, lines = 2).apply { gravity = Gravity.CENTER }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(circleButton(R.drawable.ic_prev, 56) { seek(-1) }, lp(dp(56), dp(56)))
            addView(pill("−0.1") { step(-1) }, lp(WRAP, dp(48)).margins(l = dp(12)))
            addView(pill("+0.1") { step(1) }, lp(WRAP, dp(48)).margins(l = dp(8)))
            addView(circleButton(R.drawable.ic_next, 56) { seek(1) }, lp(dp(56), dp(56)).margins(l = dp(12)))
            saveButton = circleButton(R.drawable.ic_star_border, 56) { saveOrRemove() }
            addView(saveButton, lp(dp(56), dp(56)).margins(l = dp(24)))
            addView(pill("İstasyon listesi", R.drawable.ic_radio) { ui.pop() }, lp(WRAP, dp(48)).margins(l = dp(12)))
        }

        cities = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            addView(cities, lp(MATCH, WRAP).margins(b = dp(4)))
            addView(nixie, lp(MATCH, 0, 1.2f))
            addView(stationName, lp(MATCH, WRAP).margins(t = dp(4)))
            addView(stationInfo, lp(MATCH, WRAP).margins(t = dp(2)))
            addView(dial, lp(MATCH, dp(64)).margins(t = dp(8)))
            addView(controls, lp(MATCH, WRAP).margins(t = dp(10)))
        }
        // Ekranın herhangi bir yerinde yatay kaydırma skalayı çevirir (araçta kaydırma ekranın ortasından başlar)
        val root = object : FrameLayout(this) {
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            private var downX = 0f
            private var lastX = 0f
            private var dragging = false
            private var carry = 0f

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; lastX = ev.x; dragging = false; carry = 0f }
                    MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop) { dragging = true; lastX = ev.x }
                }
                return dragging
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.x; lastX = ev.x; carry = 0f }
                    MotionEvent.ACTION_MOVE -> {
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // Parmak sola gidince frekans artar (skala sola kayar)
                        carry += (lastX - ev.x) / dpf(7)
                        lastX = ev.x
                        val steps = carry.toInt()
                        if (steps != 0) { carry -= steps; setFreq(freq + steps, play = true) }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
                }
                return true
            }
        }
        root.addView(column, flp(MATCH, MATCH))
        scope.launch { PlayerHub.now.collect { refreshInfo() } }
        scope.launch {
            Prefs.looks.collect { nixie.style = Prefs.fmStyle; dial.pointer = nixie.pointerColor; dial.invalidate() }
        }
        scope.launch { FmPresets.changes.collect { dial.invalidate(); refreshInfo(); refreshCities() } }
        com.newrex.auto.DriveState.bestLocation(ctx)?.let { FmPresets.autoCity(it.latitude, it.longitude) }
        setFreq(freq, play = false)
        root
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun step(d: Int) = setFreq(freq + d, play = true)

    /** Şehir seçimi: hazır frekanslar şehre göre değişir. */
    private fun refreshCities(): Unit = with(ctx) {
        cities.removeAllViews()
        FmPresets.CITIES.keys.forEach { c ->
            cities.addView(pill(c, selected = c == FmPresets.city, heightDp = 34) { FmPresets.city = c },
                lp(WRAP, dp(34)).margins(r = dp(8)))
        }
    }

    /** Bir sonraki / önceki kayıtlı istasyona atlar. */
    private fun seek(dir: Int) {
        val list = FmPresets.all().map { it.freq }
        val next = if (dir > 0) list.firstOrNull { it > freq } ?: list.firstOrNull()
        else list.lastOrNull { it < freq } ?: list.lastOrNull()
        if (next != null) setFreq(next, play = true, immediate = true)
    }

    private fun setFreq(f: Int, play: Boolean, immediate: Boolean = false) {
        freq = f.coerceIn(FmPresets.MIN, FmPresets.MAX)
        FmPresets.lastFreq = freq
        nixie.freq = freq
        dial.freq = freq
        refreshInfo()
        handler.removeCallbacks(tuneLater)
        // Kaydırırken her frekansta yayın açılmasın; durunca çalar
        if (play) handler.postDelayed(tuneLater, if (immediate) 0 else 450)
    }

    private fun tuneNow() {
        val preset = FmPresets.at(freq) ?: run {
            if (PlayerHub.now.value?.kind == Kind.RADIO) PlayerHub.stop()
            return
        }
        scope.launch {
            val entry = FmPresets.resolve(preset)
            if (entry == null) { stationInfo.text = "${preset.title} şu an bulunamadı."; return@launch }
            if (freq != preset.freq) return@launch
            if (PlayerHub.now.value?.url == entry.url) return@launch
            val list = FmPresets.all().mapNotNull { FmPresets.cached(it) }
            val i = list.indexOfFirst { it.url == entry.url }
            if (i >= 0) PlayerHub.play(list, i) else PlayerHub.play(listOf(entry), 0)
        }
    }

    private fun refreshInfo() {
        val preset = FmPresets.at(freq)
        val playing = PlayerHub.now.value?.takeIf { it.kind == Kind.RADIO }
        stationName.text = preset?.title ?: "— Boş frekans —"
        stationName.setTextColor(if (preset != null) Palette.TEXT else Palette.TEXT_FAINT)
        stationInfo.text = when {
            preset != null && preset.builtIn -> "${FmPresets.city} frekansı • ☆ ile bu frekansa başka istasyon kaydedebilirsin"
            preset != null -> "Kaydettiğin istasyon • ☆ ile kaldırabilirsin"
            playing != null -> "☆ ile \"${playing.title}\" istasyonunu bu frekansa kaydet"
            else -> "Kaydırarak ya da ◀◀ ▶▶ ile istasyon ara. Listeden bir radyo açıp ☆ ile buraya kaydedebilirsin."
        }
        val icon = (saveButton as FrameLayout).getChildAt(0) as android.widget.ImageView
        icon.setImageResource(if (preset != null && !preset.builtIn) R.drawable.ic_star else R.drawable.ic_star_border)
        icon.imageTintList = android.content.res.ColorStateList.valueOf(if (preset != null && !preset.builtIn) Palette.ORANGE else Palette.TEXT)
    }

    /** ☆: çalan radyoyu bu frekansa kaydeder ya da kayıtlı istasyonu kaldırır. */
    private fun saveOrRemove() {
        val preset = FmPresets.at(freq)
        val playing = PlayerHub.now.value?.takeIf { it.kind == Kind.RADIO }
        when {
            preset != null && !preset.builtIn && (playing == null || playing.url == FmPresets.cached(preset)?.url) -> {
                FmPresets.remove(freq)
                ui.toast("${FmPresets.format(freq)} boşaltıldı")
            }
            playing != null -> {
                FmPresets.save(freq, playing)
                ui.toast("${playing.title} → ${FmPresets.format(freq)} MHz kaydedildi")
            }
            else -> {
                // Çalan radyo yok: listeden seçilen istasyon bu frekansa kaydedilir
                ui.push(RadioPage(ui) { e: MediaEntry ->
                    FmPresets.save(freq, e)
                    ui.toast("${e.title} → ${FmPresets.format(freq)} MHz kaydedildi")
                })
            }
        }
    }

    // ---- Görünümler ----

    /** 87.5–108 MHz skalası: ortadaki ibre seçili frekans, noktalar kayıtlı istasyonlar. */
    private class DialView(context: Context) : View(context) {
        var freq = 1000
            set(v) { field = v; invalidate() }
        var pointer = FmDisplayView.ORANGE
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val perStep = 7 * d
            val cx = w / 2
            p.shader = null
            p.color = 0x22000000
            canvas.drawRoundRect(0f, 0f, w, h, 14 * d, 14 * d, p)
            text.textSize = 12 * d
            val presets = FmPresets.all().map { it.freq }.toSet()
            val first = freq - (cx / perStep).toInt() - 1
            val last = freq + (cx / perStep).toInt() + 1
            for (f in first..last) {
                if (f < FmPresets.MIN || f > FmPresets.MAX) continue
                val x = cx + (f - freq) * perStep
                val major = f % 10 == 0
                p.color = if (major) 0x99FFFFFF.toInt() else 0x40FFFFFF
                p.strokeWidth = if (major) 2 * d else 1 * d
                canvas.drawLine(x, h * (if (major) 0.42f else 0.55f), x, h * 0.72f, p)
                if (major && f % 20 == 0) {
                    text.color = Palette.TEXT_DIM
                    canvas.drawText((f / 10).toString(), x, h * 0.32f, text)
                }
                if (f in presets) {
                    p.color = Palette.accent
                    canvas.drawCircle(x, h * 0.86f, 3.5f * d, p)
                }
            }
            // İbre
            p.color = pointer
            p.strokeWidth = 3 * d
            canvas.drawLine(cx, h * 0.12f, cx, h * 0.92f, p)
            // Kenarlara doğru soluklaşma
            p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(0xFF0E111C.toInt(), 0, 0, 0xFF0E111C.toInt()),
                floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }
    }
}
