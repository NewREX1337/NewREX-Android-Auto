// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.animation.ValueAnimator
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Sayfa sayfa dikey kaydırma: parmakla yukarı çekince alttaki sayfa gelir, aşağı çekince üstteki.
 * Sayfanın içinde kaydırılabilir bir liste varsa önce liste sonuna kadar kayar, sonra sayfa değişir.
 * Android Auto'nun kaydırmaları da sahte parmak hareketi olarak geldiği için aynı şekilde çalışır.
 */
class VerticalPager(context: Context) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startPos = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    /** Sayfa numarası (0'dan başlar); küsuratlı değerler kaydırma sırasındaki ara konumdur. */
    var position = 0f
        private set

    /** true iken kaydırma kapalı (ör. düzenleme modunda ya da bir alt sayfa açıkken). */
    var locked = false

    var onPosition: ((Float) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null

    val page: Int get() = kotlin.math.round(position).toInt()
    private val last: Int get() = (childCount - 1).coerceAtLeast(0)

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        apply(position)
    }

    fun goTo(page: Int, animate: Boolean = true) {
        anim?.cancel()
        val target = page.coerceIn(0, childCount - 1).toFloat()
        if (!animate) { setPos(target); onPage?.invoke(page); return }
        anim = ValueAnimator.ofFloat(position, target).apply {
            duration = (200 + 300 * abs(target - position)).toLong()
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setPos(it.animatedValue as Float) }
            start()
        }
        onPage?.invoke(page)
    }

    private fun setPos(p: Float) {
        position = p
        apply(p)
        onPosition?.invoke(p)
    }

    private fun apply(p: Float) {
        val h = height.toFloat()
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.translationY = (i - p) * h
            c.visibility = if (abs(i - p) >= 1f) View.INVISIBLE else View.VISIBLE
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (locked || childCount < 2) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                startPos = position
                dragging = false
                tracker?.recycle()
                tracker = VelocityTracker.obtain().apply { addMovement(ev) }
                if (anim?.isRunning == true) { anim?.cancel(); dragging = true }
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                detect(ev)
            }
        }
        return dragging
    }

    private fun detect(ev: MotionEvent) {
        val dy = ev.y - downY
        val dx = ev.x - downX
        if (abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
            val current = getChildAt(page)
            val y = downY - current.translationY
            val wantsNext = dy < 0 && page < last && !canScroll(current, downX, y, 1)
            val wantsPrev = dy > 0 && page > 0 && !canScroll(current, downX, y, -1)
            if (wantsNext || wantsPrev) { dragging = true; downY = ev.y }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (locked || childCount < 2) return false
        if (!dragging) {
            // Hiçbir alt görünüm dokunmayı almadıysa hareket doğrudan buraya gelir
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) return true
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) { tracker?.addMovement(ev); detect(ev) }
            if (!dragging) return true
        }
        tracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val h = height.coerceAtLeast(1)
                // Bir hareketle en fazla bir sayfa ilerlenir; kenarlarda hafif esneme
                val from = kotlin.math.round(startPos)
                var p = (startPos - (ev.y - downY) / h).coerceIn(from - 1f, from + 1f)
                if (p < 0f) p *= 0.3f
                if (p > last) p = last + (p - last) * 0.3f
                setPos(p)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                t?.computeCurrentVelocity(1000)
                val vy = t?.yVelocity ?: 0f
                val fast = context.dpf(500)
                val from = kotlin.math.round(startPos).toInt()
                val target = when {
                    vy < -fast && position > from -> from + 1
                    vy > fast && position < from -> from - 1
                    else -> kotlin.math.round(position).toInt()
                }.coerceIn(0, last)
                dragging = false
                tracker?.recycle(); tracker = null
                goTo(target)
            }
        }
        return true
    }

    /** Dokunulan noktadaki kaydırılabilir bir görünüm o yöne (-1 yukarı, 1 aşağı) kayabiliyor mu? */
    private fun canScroll(v: View, x: Float, y: Float, direction: Int): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v.canScrollVertically(direction)) return true
        if (v !is ViewGroup) return false
        for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            val cx = x + v.scrollX - c.left - c.translationX
            val cy = y + v.scrollY - c.top - c.translationY
            if (cx >= 0 && cy >= 0 && cx < c.width && cy < c.height && canScroll(c, cx, cy, direction)) return true
        }
        return false
    }
}
