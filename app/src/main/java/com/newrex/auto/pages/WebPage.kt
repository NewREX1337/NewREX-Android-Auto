// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.pill
import com.newrex.auto.ui.circleButton
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.flp
import com.newrex.auto.ui.parkGuard
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder

/** YouTube ve genel web tarayıcısı. Sürüş sırasında görüntü gizlenir. */
class WebPage(ui: CarUi, private val mode: Mode) : Page(ui) {
    enum class Mode(val title: String, val home: String) {
        YOUTUBE("YouTube", "https://m.youtube.com"),
        WEB("Web", "https://www.google.com"),
    }

    override val title = mode.title

    private companion object {
        val YOUTUBE_CATEGORIES = listOf(
            "Popüler" to "türkiye gündem",
            "Müzik" to "türkçe pop yeni şarkılar",
            "Haber" to "son dakika haber",
            "Spor" to "maç özeti",
            "Canlı" to "canlı yayın türkiye",
            "Komedi" to "komedi skeç",
            "Çocuk" to "çocuk şarkıları",
            "Podcast" to "türkçe podcast",
        )
        var selectedCategory = 0

        private const val FOCUSED_FIELD_JS = """(function(){
            var e = document.activeElement; if (!e) return null;
            var t = (e.type || '').toLowerCase();
            var skip = ['button','submit','checkbox','radio','range','color','file','image','reset','hidden'];
            var isText = e.isContentEditable || e.tagName == 'TEXTAREA' || (e.tagName == 'INPUT' && skip.indexOf(t) < 0);
            if (!isText) return null;
            return JSON.stringify({ type: t, value: t == 'password' ? '' : (e.value || e.innerText || ''),
                hint: e.placeholder || e.getAttribute('aria-label') || e.name || '' });
        })()"""

        // React gibi kütüphaneler değeri doğrudan atamayı fark etmediği için yerel setter + input olayı
        private const val FILL_FIELD_JS = """function(v){
            var e = document.activeElement; if (!e) return;
            if (e.isContentEditable) { e.innerText = v; }
            else {
                var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value');
                if (d && d.set) d.set.call(e, v); else e.value = v;
            }
            e.dispatchEvent(new Event('input', { bubbles: true }));
            e.dispatchEvent(new Event('change', { bubbles: true }));
        }"""
    }

    private lateinit var categories: LinearLayout
    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenBox: FrameLayout
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun build(): View = with(ctx) {
        // YouTube açılınca radyo/TV sesi ile çakışmasın
        if (mode == Mode.YOUTUBE) PlayerHub.stop()

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(Palette.accent)
        }
        fullscreenBox = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        web = WebView(this).apply {
            setBackgroundColor(0xFF0B0E1A.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(false)
            // Web sayfaları telefondaki dosyalara ve diğer uygulamaların verilerine erişemesin
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.safeBrowsingEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Uygulama açma bağlantılarını (intent://, vnd.youtube:) engelle, sayfada kal
                    val scheme = request.url.scheme ?: return true
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    progressBar.visibility = View.GONE
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                }

                // Videonun tam ekran butonu
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    customView = view
                    customCallback = callback
                    fullscreenBox.addView(view, flp(MATCH, MATCH))
                    fullscreenBox.visibility = View.VISIBLE
                }

                override fun onHideCustomView() = exitFullscreen()
            }
            // Sayfadaki bir yazı kutusuna dokunulunca kendi klavyemizi aç
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_UP) postDelayed({ checkFocusedField() }, 350)
                false
            }
            loadUrl(startUrl())
        }

        val webBox = FrameLayout(this).apply {
            addView(web, flp(MATCH, MATCH))
            addView(progressBar, flp(MATCH, dp(4)))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (mode == Mode.YOUTUBE) {
                categories = LinearLayout(this@with).apply { orientation = LinearLayout.HORIZONTAL }
                addView(HorizontalScrollView(this@with).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(categories)
                }, lp(MATCH, WRAP).margins(b = dp(6)))
                refreshCategories()
            }
            addView(webBox, lp(MATCH, 0, 1f))
        }
        FrameLayout(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(column, flp(MATCH, MATCH))
            addView(fullscreenBox, flp(MATCH, MATCH))
            addView(parkGuard(), flp(MATCH, MATCH))
        }
    }

    /** Hesap girişi olmadan YouTube'u kullanılabilir kılan hızlı kategoriler. */
    private fun refreshCategories(): Unit = with(ctx) {
        categories.removeAllViews()
        YOUTUBE_CATEGORIES.forEachIndexed { i, (name, _) ->
            categories.addView(pill(name, selected = i == selectedCategory, heightDp = 36) {
                selectedCategory = i
                refreshCategories()
                web.loadUrl(categoryUrl(i))
            }, lp(WRAP, dp(36)).margins(r = dp(8)))
        }
    }

    private fun categoryUrl(i: Int) =
        "https://m.youtube.com/results?search_query=" + URLEncoder.encode(YOUTUBE_CATEGORIES[i].second, "UTF-8")

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_search) {
            val hint = if (mode == Mode.YOUTUBE) "YouTube'da ara" else "Ara veya adres yaz"
            ui.showKeyboard(hint) { open(it) }
        },
        ctx.circleButton(R.drawable.ic_home) { web.loadUrl(startUrl()) },
        ctx.circleButton(R.drawable.ic_refresh) { web.reload() },
    )

    private fun startUrl() = if (mode == Mode.YOUTUBE) categoryUrl(selectedCategory) else mode.home

    private fun open(input: String) {
        val q = URLEncoder.encode(input, "UTF-8")
        val url = when {
            mode == Mode.YOUTUBE -> "https://m.youtube.com/results?search_query=$q"
            input.startsWith("http://") || input.startsWith("https://") -> input
            !input.contains(' ') && input.contains('.') -> "https://$input"
            else -> "https://www.google.com/search?q=$q"
        }
        web.loadUrl(url)
    }

    /**
     * Odaktaki öğe bir yazı kutusuysa klavyeyi açar; yazılanı kutuya sayfanın kendi olaylarıyla yerleştirir.
     * Yazılanlar (şifreler dahil) hiçbir yere kaydedilmez veya gönderilmez, sadece sayfaya aktarılır.
     */
    private fun checkFocusedField() {
        web.evaluateJavascript(FOCUSED_FIELD_JS) { result ->
            if (result.isNullOrEmpty() || result == "null") return@evaluateJavascript
            val info = runCatching { JSONObject(JSONTokener(result).nextValue() as String) }.getOrNull()
                ?: return@evaluateJavascript
            val secret = info.optString("type") == "password"
            val hint = info.optString("hint").ifBlank { if (secret) "Şifre" else "Yazı" }
            ui.showKeyboard(
                hint,
                initial = if (secret) "" else info.optString("value"),
                secret = secret,
                doneLabel = "Tamam",
                allowEmpty = true,
            ) { text -> web.evaluateJavascript("($FILL_FIELD_JS)(${JSONObject.quote(text)})", null) }
        }
    }

    private fun exitFullscreen() {
        customView?.let { fullscreenBox.removeView(it) }
        fullscreenBox.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
    }

    override fun onBack(): Boolean {
        if (customView != null) { exitFullscreen(); return true }
        if (web.canGoBack()) { web.goBack(); return true }
        return false
    }

    override fun onDestroy() {
        exitFullscreen()
        web.stopLoading()
        web.loadUrl("about:blank")
        web.destroy()
    }
}
