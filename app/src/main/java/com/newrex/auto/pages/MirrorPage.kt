// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.Mirror
import com.newrex.auto.MirrorTouchService
import com.newrex.auto.R
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.rounded
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.icon
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.neonText
import com.newrex.auto.ui.panel
import com.newrex.auto.ui.parkGuard
import com.newrex.auto.ui.pill
import kotlinx.coroutines.launch
import kotlin.math.hypot

/** Telefon ekranını araç ekranında gösterir; dokunmalar telefona iletilir. */
class MirrorPage(ui: CarUi) : Page(ui) {
    override val fullscreen = true

    private lateinit var texture: TextureView
    private lateinit var waiting: View
    private lateinit var touchHint: TextView
    private var surface: Surface? = null
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun build(): View = with(ctx) {
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    surface = Surface(st)
                    connect(w, h)
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = connect(w, h)

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    Mirror.detach()
                    surface?.release()
                    surface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            setOnTouchListener { _, ev -> onTouch(ev); true }
        }

        touchHint = label("", 13f, Palette.ORANGE, lines = 2).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(0xCC000000.toInt(), dpf(10))
        }

        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = rounded(0xAA0B0E1A.toInt(), dpf(22))
            fun add(res: Int, action: () -> Unit) = addView(circleButton(res, 44, onClick = action), lp(dp(44), dp(44)).margins(b = dp(8)))
            add(R.drawable.ic_close) { ui.back() }
            add(R.drawable.ic_back) { global(AccessibilityService.GLOBAL_ACTION_BACK) }
            add(R.drawable.ic_home) { global(AccessibilityService.GLOBAL_ACTION_HOME) }
            add(R.drawable.ic_recents) { global(AccessibilityService.GLOBAL_ACTION_RECENTS) }
        }

        waiting = buildWaiting()

        FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(texture, flp(MATCH, MATCH))
            addView(touchHint, flp(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).margins(t = dp(8)))
            addView(waiting, flp(MATCH, MATCH))
            addView(parkGuard(), flp(MATCH, MATCH))
            addView(side, flp(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).margins(r = dp(8)))
        }.also {
            if (!Mirror.active.value) Mirror.requestStart(ctx.applicationContext)
            scope.launch {
                Mirror.active.collect { on ->
                    waiting.visibility = if (on) View.GONE else View.VISIBLE
                    texture.visibility = if (on) View.VISIBLE else View.INVISIBLE
                    if (on && texture.isAvailable) connect(texture.width, texture.height)
                    refreshTouchHint()
                }
            }
        }
    }

    override fun onShow() = refreshTouchHint()

    private fun buildWaiting(): View = with(ctx) {
        val box = panel().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(18))
            addView(icon(R.drawable.ic_cast, 48, Palette.accent), lp(dp(48), dp(48)))
            addView(label("Ekran yansıtma", 22f, bold = true).apply { neonText(10) }, lp(WRAP, WRAP).margins(t = dp(10)))
            addView(label("Telefonunda ekran paylaşımı onayı çıkacak, \"Başlat\"a dokun.\n" +
                "Çıkmazsa: telefonda NewREX uygulamasını aç → \"Ekranı arabaya yansıt\".",
                14f, Palette.TEXT_DIM, lines = 4).apply { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(t = dp(8)))
            addView(pill("Telefonda onayı tekrar iste", R.drawable.ic_cast) { Mirror.requestStart(ctx.applicationContext) },
                lp(WRAP, dp(44)).margins(t = dp(14)))
        }
        FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0E1A.toInt())
            isClickable = true
            addView(box, flp(WRAP, WRAP, Gravity.CENTER).margins(dp(20), dp(20), dp(80), dp(20)))
        }
    }

    private fun refreshTouchHint() {
        val enabled = Mirror.touchEnabled(ctx) && MirrorTouchService.instance != null
        touchHint.text = "Dokunma kapalı: telefonda NewREX → \"Dokunma iznini aç\""
        touchHint.visibility = if (enabled || !Mirror.active.value) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        Mirror.detach()
        surface?.release()
        surface = null
    }

    private fun connect(w: Int, h: Int) {
        val s = surface ?: return
        if (w == 0 || h == 0 || !Mirror.active.value) return
        Mirror.attach(s, w, h, ctx.resources.displayMetrics.densityDpi)
    }

    private fun global(action: Int) {
        val svc = MirrorTouchService.instance
        if (svc == null) { ui.toast("Önce telefonda dokunma iznini aç."); return }
        svc.performGlobalAction(action)
    }

    // Araç ekranındaki nokta → telefon ekranındaki nokta (görüntü ortalanıp sığdırılıyor)
    private fun toPhone(x: Float, y: Float): Pair<Float, Float>? {
        val size = Mirror.phoneSize(ctx)
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw == 0f || vh == 0f || size.x == 0 || size.y == 0) return null
        val scale = minOf(vw / size.x, vh / size.y)
        val offX = (vw - size.x * scale) / 2
        val offY = (vh - size.y * scale) / 2
        val px = (x - offX) / scale
        val py = (y - offY) / scale
        if (px < 0 || py < 0 || px > size.x || py > size.y) return null
        return px to py
    }

    private fun onTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downTime = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val svc = MirrorTouchService.instance ?: run { refreshTouchHint(); return }
                val start = toPhone(downX, downY) ?: return
                val moved = hypot(ev.x - downX, ev.y - downY) > ctx.dp(12)
                if (!moved) {
                    svc.tap(start.first, start.second)
                } else {
                    val end = toPhone(ev.x, ev.y) ?: toPhone(ev.x.coerceIn(0f, texture.width.toFloat()), ev.y.coerceIn(0f, texture.height.toFloat())) ?: return
                    val dur = (SystemClock.uptimeMillis() - downTime).coerceIn(120, 800)
                    svc.stroke(start.first, start.second, end.first, end.second, dur)
                }
            }
        }
    }
}
