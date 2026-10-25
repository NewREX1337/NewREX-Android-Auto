// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.rounded

/**
 * Araç klavyesi yerine telefonun kendi klavyesiyle yazma. Android Auto hızlı art arda dokunmaları
 * birleştirdiği için araç ekranında hızlı yazmak zor; park halindeyken telefondan yazılıp araca aktarılır.
 * Yazılan metin sadece araç ekranındaki kutuya gider, hiçbir yere kaydedilmez.
 */
object PhoneInput {
    private var callback: ((String) -> Unit)? = null
    internal var initial = ""
    internal var hint = ""
    internal var secret = false

    fun request(context: Context, initial: String, hint: String, secret: Boolean, onText: (String) -> Unit) {
        callback = onText
        this.initial = initial
        this.hint = hint
        this.secret = secret
        context.startActivity(Intent(context, PhoneInputActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    fun cancel() { callback = null }

    internal fun deliver(text: String) {
        val cb = callback ?: return
        callback = null
        Handler(Looper.getMainLooper()).post { cb(text) }
    }
}

class PhoneInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val field = EditText(this).apply {
            setText(PhoneInput.initial)
            setSelection(text.length)
            hint = PhoneInput.hint
            setTextColor(Palette.TEXT)
            setHintTextColor(Palette.TEXT_FAINT)
            textSize = 18f
            setSingleLine()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0x22FFFFFF, dpf(12), dp(1), 0x44FFFFFF)
            inputType = if (PhoneInput.secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, _, _ -> send(v.text.toString()); true }
        }
        fun button(text: String, bg: Int, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Palette.TEXT)
            background = rounded(bg, dpf(12))
            setOnClickListener { onClick() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.BG_TOP)
            setPadding(dp(20), dp(40), dp(20), dp(40))
            addView(label("Araç ekranına yaz", 22f, bold = true))
            addView(label("Yazdığın, araç ekranındaki kutuya aktarılır.", 14f, Palette.TEXT_DIM, lines = 2),
                lp(MATCH, WRAP).margins(t = dp(6), b = dp(18)))
            addView(field, lp(MATCH, WRAP))
            addView(button("Araca gönder", Palette.accents[0]) { send(field.text.toString()) }, lp(MATCH, WRAP).margins(t = dp(16)))
            addView(button("Vazgeç", 0x22FFFFFF) { finish() }, lp(MATCH, WRAP).margins(t = dp(10)))
        })
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        field.requestFocus()
    }

    private fun send(text: String) {
        PhoneInput.deliver(text)
        finish()
    }
}
