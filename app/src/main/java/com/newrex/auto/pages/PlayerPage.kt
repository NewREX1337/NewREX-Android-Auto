// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.gradient
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.parkGuard
import com.newrex.auto.ui.rounded
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Tam ekran TV oynatıcı. Ekrana dokununca kontroller açılır/kapanır. */
class PlayerPage(ui: CarUi) : Page(ui) {
    override val fullscreen = true

    private val handler = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { setControls(false) }
    private lateinit var playerView: PlayerView
    private lateinit var controls: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var playButton: FrameLayout
    private lateinit var favButton: FrameLayout
    private lateinit var spinner: ProgressBar
    private lateinit var errorView: TextView

    override fun build(): View = with(ctx) {
        playerView = LayoutInflater.from(this).inflate(R.layout.player_view, null) as PlayerView
        playerView.player = PlayerHub.player

        spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Palette.TEXT)
        }
        errorView = label("", 17f, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = rounded(0xCC000000.toInt(), dpf(14))
        }

        titleView = label("", 22f, bold = true)
        subView = label("", 13f, Palette.TEXT_DIM)
        favButton = circleButton(R.drawable.ic_star_border, 52) {
            PlayerHub.now.value?.let { Prefs.toggleFavorite(it) }
            bumpControls()
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), maxOf(dp(16), ui.topRightReserve), dp(28))
            background = gradient(0xCC000000.toInt(), 0x00000000, orientation = GradientDrawable.Orientation.TOP_BOTTOM)
            addView(circleButton(R.drawable.ic_back, 52) { ui.back() }, lp(dp(52), dp(52)).margins(r = dp(14)))
            addView(LinearLayout(this@with).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(subView, lp(WRAP, WRAP).margins(t = dp(4)))
            }, lp(0, WRAP, 1f))
            addView(favButton, lp(dp(52), dp(52)))
        }

        playButton = circleButton(R.drawable.ic_pause, 72, bg = Palette.accent) { PlayerHub.toggle(); bumpControls() }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(18))
            background = gradient(0x00000000, 0xCC000000.toInt(), orientation = GradientDrawable.Orientation.TOP_BOTTOM)
            addView(circleButton(R.drawable.ic_prev, 60) { PlayerHub.prev(); bumpControls() }, lp(dp(60), dp(60)))
            addView(playButton, lp(dp(72), dp(72)).margins(dp(28), 0, dp(28), 0))
            addView(circleButton(R.drawable.ic_next, 60) { PlayerHub.next(); bumpControls() }, lp(dp(60), dp(60)))
        }

        controls = FrameLayout(this).apply {
            addView(top, flp(MATCH, WRAP, Gravity.TOP))
            addView(bottom, flp(MATCH, WRAP, Gravity.BOTTOM))
        }

        FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(playerView, flp(MATCH, MATCH))
            addView(spinner, flp(dp(56), dp(56), Gravity.CENTER))
            addView(errorView, flp(WRAP, WRAP, Gravity.CENTER))
            addView(parkGuard(), flp(MATCH, MATCH))
            addView(controls, flp(MATCH, MATCH))
            // Boş bir yere dokunmak kontrolleri açıp kapatır
            setOnClickListener { setControls(controls.visibility != View.VISIBLE) }
            controls.setOnClickListener { setControls(false) }
        }.also { observe() }
    }

    private fun observe() {
        scope.launch {
            combine(PlayerHub.now, Prefs.changes) { e, _ -> e }.collect { e ->
                titleView.text = e?.title.orEmpty()
                subView.text = e?.group.orEmpty()
                val fav = e != null && Prefs.isFavorite(e)
                (favButton.getChildAt(0) as ImageView).apply {
                    setImageResource(if (fav) R.drawable.ic_star else R.drawable.ic_star_border)
                    imageTintList = ColorStateList.valueOf(if (fav) Palette.ORANGE else Palette.TEXT)
                }
            }
        }
        scope.launch {
            PlayerHub.playing.collect { playing ->
                (playButton.getChildAt(0) as ImageView).setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                if (playing) bumpControls() else { handler.removeCallbacks(hideControls); setControls(true, autoHide = false) }
            }
        }
        scope.launch { PlayerHub.buffering.collect { spinner.visibility = if (it) View.VISIBLE else View.GONE } }
        scope.launch {
            PlayerHub.error.collect { err ->
                errorView.visibility = if (err == null) View.GONE else View.VISIBLE
                errorView.text = err.orEmpty()
                if (err != null) setControls(true, autoHide = false)
            }
        }
    }

    private fun setControls(show: Boolean, autoHide: Boolean = true) {
        controls.visibility = if (show) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideControls)
        if (show && autoHide && PlayerHub.playing.value) handler.postDelayed(hideControls, 4000)
    }

    private fun bumpControls() = setControls(true)

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Görüntü kapanır, ses mini oynatıcıda devam eder
        playerView.player = null
    }
}
