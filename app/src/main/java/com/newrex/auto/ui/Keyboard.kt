// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.ui

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.newrex.auto.PhoneInput
import com.newrex.auto.R
import java.util.Locale

/**
 * Araç ekranı için Türkçe Q klavye. Telefonun klavyesi araç ekranında açılamadığı için
 * arama, adres ve web sayfalarındaki giriş kutuları için kendi klavyemizi çiziyoruz.
 */
class KeyboardView(
    context: Context,
    hint: String,
    initial: String,
    private val secret: Boolean,
    doneLabel: String,
    private val onDone: (String) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {

    private enum class Layout { LETTERS, SYMBOLS }

    private val letterRows = listOf("1234567890", "qwertyuıopğü", "asdfghjklşi", "zxcvbnmöç.")
    private val symbolRows = listOf("1234567890", "@#₺%&*-_+=", "!?/\\:;'\"()", ".,<>[]{}~^|")
    private val trLocale = Locale("tr", "TR")

    private val text = StringBuilder(initial)
    private val hintText = hint
    private val input: TextView
    private val keyRows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var layout = Layout.LETTERS
    private var shift = false

    init {
        // Arkadaki sayfaya dokunmayı engelle
        setBackgroundColor(0xCC05060C.toInt())
        isClickable = true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF2161A2B.toInt(), context.dpf(20))
            setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(8))
        }
        input = context.label("", 19f).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(16), 0)
            background = rounded(Palette.SURFACE_STRONG, context.dpf(12))
        }
        panel.addView(input, lp(MATCH, context.dp(44)).margins(b = context.dp(6)))
        panel.addView(keyRows, lp(MATCH, WRAP))

        val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(key("Kapat") { onClose() }, keyLp(1.4f))
        // Hızlı yazmak için telefonun kendi klavyesi (araçta hızlı dokunmalar birleşebiliyor)
        bottom.addView(key("📱 Telefon") {
            PhoneInput.request(context.applicationContext, text.toString(), hintText, secret) { typed ->
                text.setLength(0)
                text.append(typed.take(200))
                refresh()
            }
        }, keyLp(1.6f))
        bottom.addView(key("?123") {
            layout = if (layout == Layout.LETTERS) Layout.SYMBOLS else Layout.LETTERS
            renderKeys()
        }.also { modeKey = it }, keyLp(1.2f))
        bottom.addView(key("boşluk") { type(" ") }, keyLp(3.4f))
        bottom.addView(iconKey(R.drawable.ic_backspace) {
            if (text.isNotEmpty()) text.deleteCharAt(text.length - 1)
            refresh()
        }, keyLp(1.4f))
        bottom.addView(key(doneLabel, Palette.accent) { onDone(text.toString()) }, keyLp(1.8f))
        panel.addView(bottom, lp(MATCH, WRAP))

        addView(panel, flp(MATCH, WRAP, Gravity.BOTTOM).margins(context.dp(12), 0, context.dp(12), context.dp(8)))
        renderKeys()
        refresh()
    }

    private lateinit var modeKey: TextView

    override fun onDetachedFromWindow() {
        PhoneInput.cancel()
        super.onDetachedFromWindow()
    }

    private fun renderKeys() {
        keyRows.removeAllViews()
        val rows = if (layout == Layout.LETTERS) letterRows else symbolRows
        rows.forEachIndexed { i, row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            // Harf düzeninde son satırın başına büyük harf tuşu
            if (layout == Layout.LETTERS && i == rows.lastIndex) {
                line.addView(key("⇧", if (shift) Palette.accent else Palette.SURFACE_STRONG) {
                    shift = !shift
                    renderKeys()
                }, keyLp(1.4f))
            }
            row.forEach { ch ->
                val s = if (shift && layout == Layout.LETTERS) ch.toString().uppercase(trLocale) else ch.toString()
                line.addView(key(s) {
                    type(s)
                    if (shift) { shift = false; renderKeys() }
                }, keyLp(1f))
            }
            keyRows.addView(line, lp(MATCH, WRAP))
        }
        if (::modeKey.isInitialized) modeKey.text = if (layout == Layout.LETTERS) "?123" else "abc"
    }

    private fun type(s: String) {
        if (text.length < 200) text.append(s)
        refresh()
    }

    private fun refresh() {
        if (text.isEmpty()) {
            input.text = hintText
            input.setTextColor(Palette.TEXT_FAINT)
        } else {
            val shown = if (secret) "•".repeat(text.length) else text.toString()
            input.text = "$shown|"
            input.setTextColor(Palette.TEXT)
        }
    }

    private fun keyLp(weight: Float) =
        lp(0, context.dp(42), weight).margins(context.dp(3), context.dp(3), context.dp(3), context.dp(3))

    private fun key(t: String, bg: Int = Palette.SURFACE_STRONG, action: () -> Unit) =
        context.label(t, 18f).apply {
            gravity = Gravity.CENTER
            val r = context.dpf(10)
            background = pressable(rounded(bg, r), r)
            onTap(immediate = true, action = action)
        }

    private fun iconKey(res: Int, action: () -> Unit) = FrameLayout(context).apply {
        val r = context.dpf(10)
        background = pressable(rounded(Palette.SURFACE_STRONG, r), r)
        addView(context.icon(res, 22), flp(context.dp(22), context.dp(22), Gravity.CENTER))
        onTap(immediate = true, action = action)
    }
}
