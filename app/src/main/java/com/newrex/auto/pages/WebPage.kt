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
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.newrex.auto.PlayerHub
import com.newrex.auto.R
import com.newrex.auto.data.Prefs
import com.newrex.auto.ui.CarUi
import com.newrex.auto.ui.dpf
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Page
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
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
        // Turkcell TV+: kullanıcı kendi hesabıyla girer; giriş bilgileri sadece sayfa ile TV+ arasında kalır
        TVPLUS("TV+", "https://tvplus.com.tr/canli-tv"),
        NETFLIX("Netflix", "https://www.netflix.com/browse"),
    }

    override val title = mode.title
    // Video tam ekrana geçince üst bar ve mini oynatıcı da gizlenir
    override val fullscreen: Boolean get() = customView != null

    private companion object {

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
        // Doğrulama kodu ekranları (ör. TV+) her rakam için ayrı, tek haneli kutu kullanır: kod kutulara
        // birer birer dağıtılır (her birinde sayfanın kendi olayı çalışsın diye aralıklı)
        private const val FILL_FIELD_JS = """function(v){
            var e = document.activeElement; if (!e) return;
            function put(el, s) {
                if (el.isContentEditable) { el.innerText = s; }
                else {
                    var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
                    if (d && d.set) d.set.call(el, s); else el.value = s;
                }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
            }
            if (e.tagName == 'INPUT' && e.maxLength == 1 && v.length > 1) {
                var box = e.parentElement, boxes = [];
                while (box && boxes.length < 2) {
                    boxes = [].filter.call(box.querySelectorAll('input'), function(i){ return i.maxLength == 1 && i.offsetParent; });
                    box = box.parentElement;
                }
            }
            if (boxes && boxes.length > 1) {
                var start = Math.max(0, boxes.indexOf(e)), chars = v.replace(/\s/g, '').split('');
                chars.slice(0, boxes.length - start).forEach(function(c, k){
                    setTimeout(function(){ var b = boxes[start + k]; b.focus(); put(b, c); }, k * 60);
                });
                return;
            }
            put(e, v);
        }"""
    }

    private lateinit var web: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var fullscreenBox: FrameLayout
    private lateinit var fitButton: View
    private lateinit var pageRoot: FrameLayout
    private val hideFit = Runnable { fitButton.animate().alpha(0f).setDuration(300).withEndAction { fitButton.visibility = View.GONE }.start() }
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
        // Tam ekranda dokununca birkaç saniye "ekrana sığdır" düğmesi görünür
        fullscreenBox = object : FrameLayout(this) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) showFitButton()
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        fitButton = fitPill()
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
            // Hesaba bir kez girilince oturum kalsın (TV+, Netflix, YouTube): çerezler telefonda saklanır, silinmez
            val view = this
            android.webkit.CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(view, true)
            }
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
                    fullscreenBox.addView(view, 0, flp(MATCH, MATCH))
                    fullscreenBox.visibility = View.VISIBLE
                    pageRoot.setPadding(0, 0, 0, 0)
                    applyFit()
                    ui.refreshChrome()
                    showFitButton()
                }

                override fun onHideCustomView() = exitFullscreen()

                // Şifreli (DRM) yayınlar için oynatıcı izni: sadece TV+ sayfasına verilir
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val host = request.origin?.host.orEmpty()
                    val drm = android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    val site = when (mode) { Mode.TVPLUS -> "tvplus.com.tr"; Mode.NETFLIX -> "netflix.com"; else -> null }
                    if (site != null && (host == site || host.endsWith(".$site")) && drm in request.resources) request.grant(arrayOf(drm))
                    else request.deny()
                }
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
            addView(webBox, lp(MATCH, 0, 1f))
        }
        fullscreenBox.addView(fitButton, flp(WRAP, dp(44), android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL)
            .margins(t = dp(14)))
        pageRoot = FrameLayout(this).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(column, flp(MATCH, MATCH))
            addView(fullscreenBox, flp(MATCH, MATCH))
            addView(parkGuard(), flp(MATCH, MATCH))
        }
        pageRoot
    }

    /** Videonun ekrana yerleşimini değiştiren düğme: Sığdır → Doldur → Uzat. */
    private fun fitPill(): View = with(ctx) {
        val names = arrayOf("Ekrana sığdır", "Ekranı doldur", "Uzat")
        android.widget.TextView(this).apply {
            text = "⛶  " + names[Prefs.videoFit]
            setTextColor(Palette.TEXT)
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = com.newrex.auto.ui.rounded(0xAA000000.toInt(), dpf(22), dp(1), 0x55FFFFFF)
            setOnClickListener {
                Prefs.videoFit = (Prefs.videoFit + 1) % 3
                text = "⛶  " + names[Prefs.videoFit]
                applyFit()
                showFitButton()
            }
        }
    }

    private fun showFitButton() {
        if (!::fitButton.isInitialized || customView == null) return
        fitButton.removeCallbacks(hideFit)
        fitButton.animate().cancel()
        fitButton.visibility = View.VISIBLE
        fitButton.alpha = 1f
        fitButton.postDelayed(hideFit, 3500)
    }

    /**
     * Sayfadaki videolara seçili yerleşimi uygular (kenardaki siyah bantlar için). YouTube video kutusunu
     * 16:9 boyutlandırıp kendisi konumlandırdığı için kutu yerinden oynatılmaz; ölçülüp ekranı kaplayacak
     * kadar büyütülür ("doldur" oranı korur, kenarları kırpar; "uzat" ekrana göre esnetir).
     */
    private fun applyFit(active: Boolean = true) {
        val mode = if (active) Prefs.videoFit else 0
        web.evaluateJavascript("""(function(){
            var mode = $mode;
            var s = document.getElementById('newrex-fit');
            if (!s) { s = document.createElement('style'); s.id = 'newrex-fit'; document.head.appendChild(s); }
            window.__newrexFit = mode;
            function fit() {
                s.textContent = '';
                if (window.__newrexFit == 0) return;
                var v = document.querySelector('video');
                if (!v) return;
                var r = v.getBoundingClientRect();
                if (r.width < 10 || r.height < 10) return;
                var W = window.innerWidth, H = window.innerHeight;
                var sx = W / r.width, sy = H / r.height;
                if (window.__newrexFit == 1) { sx = sy = Math.max(sx, sy); }
                var dx = W / 2 - (r.left + r.width / 2), dy = H / 2 - (r.top + r.height / 2);
                s.textContent = 'video { transform-origin: center center !important; transform: translate(' + dx + 'px,' + dy + 'px) scale(' + sx + ',' + sy + ') !important; }';
            }
            if (!window.__newrexFitHooked) {
                window.__newrexFitHooked = true;
                window.addEventListener('resize', function(){ setTimeout(fit, 300); });
            }
            fit(); setTimeout(fit, 400); setTimeout(fit, 1200);
        })()""", null)
    }

    override fun actions(): List<View> = listOf(
        ctx.circleButton(R.drawable.ic_search) {
            val hint = if (mode == Mode.YOUTUBE) "YouTube'da ara" else "Ara veya adres yaz"
            ui.showKeyboard(hint) { open(it) }
        },
        ctx.circleButton(R.drawable.ic_home) { web.loadUrl(startUrl()) },
        ctx.circleButton(R.drawable.ic_refresh) { web.reload() },
    )

    // YouTube her açılışta kendi ana sayfasıyla gelir (kategoriler YouTube'un kendi satırında)
    private fun startUrl() = mode.home

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

    private fun exitFullscreen(refresh: Boolean = true) {
        val wasFull = customView != null
        // Tam ekrandan çıkınca sayfadaki normal video boyutuna dön
        if (wasFull) applyFit(active = false)
        customView?.let { fullscreenBox.removeView(it) }
        fullscreenBox.visibility = View.GONE
        customCallback?.onCustomViewHidden()
        customView = null
        customCallback = null
        if (wasFull && refresh && ::pageRoot.isInitialized) {
            pageRoot.setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            ui.refreshChrome()
        }
    }

    override fun onBack(): Boolean {
        if (customView != null) { exitFullscreen(); return true }
        if (web.canGoBack()) { web.goBack(); return true }
        return false
    }

    override fun onDestroy() {
        exitFullscreen(refresh = false)
        // Giriş çerezleri hemen diske yazılsın: uygulama kapansa da oturum düşmesin
        android.webkit.CookieManager.getInstance().flush()
        web.stopLoading()
        web.loadUrl("about:blank")
        web.destroy()
    }
}
