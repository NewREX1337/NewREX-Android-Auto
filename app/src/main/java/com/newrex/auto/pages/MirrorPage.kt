// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.newrex.auto.Mirror
import com.newrex.auto.R
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
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

/** Telefon ekranını araç ekranında gösterir (yalnızca görüntü; telefon kendi ekranından kullanılır). */
class MirrorPage(ui: CarUi) : Page(ui) {
    override val fullscreen = true

    private lateinit var texture: TextureView
    private lateinit var waiting: View
    private var surface: Surface? = null

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
        }

        waiting = buildWaiting()

        FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(texture, flp(MATCH, MATCH))
            addView(waiting, flp(MATCH, MATCH))
            addView(parkGuard(), flp(MATCH, MATCH))
            addView(circleButton(R.drawable.ic_close, 48) { ui.back() },
                flp(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).margins(r = dp(10)))
        }.also {
            if (!Mirror.active.value) Mirror.requestStart(ctx.applicationContext)
            scope.launch {
                Mirror.active.collect { on ->
                    waiting.visibility = if (on) View.GONE else View.VISIBLE
                    texture.visibility = if (on) View.VISIBLE else View.INVISIBLE
                    if (on && texture.isAvailable) connect(texture.width, texture.height)
                }
            }
        }
    }

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
}
