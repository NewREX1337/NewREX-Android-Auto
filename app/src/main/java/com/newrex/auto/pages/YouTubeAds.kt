// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.pages

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** YouTube reklam engelleyici. Sadece telefonda çalışır; hiçbir yere bir şey göndermez, kaydetmez. */
object YouTubeAds {
    val ORIGINS = setOf("https://m.youtube.com", "https://www.youtube.com", "https://youtube.com")

    private val AD_HOSTS = listOf("doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com")
    private val AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/get_midroll_", "/youtubei/v1/player/ad_break")

    /** Reklam adresine gidecek istek boş cevapla kesilir. */
    fun block(url: Uri): WebResourceResponse? {
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()
        val ad = AD_HOSTS.any { host == it || host.endsWith(".$it") } ||
            (host.endsWith("youtube.com") && AD_PATHS.any { path.startsWith(it) })
        return if (ad) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
    }

    // 1) Video bilgisindeki reklam planı oynatıcıya ulaşmadan silinir (sayfa içi geçişler JSON ile gelir)
    // 2) Akıştaki reklam kartları gizlenir
    // 3) Yine de başlayan reklam sessize alınıp sonuna atlanır, "Atla" düğmesi varsa basılır
    const val SCRIPT = """(function(){
        if (window.__newrexAds) return; window.__newrexAds = true;
        var KEYS = ['adPlacements','adSlots','playerAds','adBreakHeartbeatParams'];
        function clean(o) {
            if (o && typeof o == 'object' && !Array.isArray(o)) {
                KEYS.forEach(function(k){ if (k in o) delete o[k]; });
                if (o.playerResponse) clean(o.playerResponse);
            }
            return o;
        }
        var parse = JSON.parse;
        JSON.parse = function(){ return clean(parse.apply(this, arguments)); };
        var json = Response.prototype.json;
        Response.prototype.json = function(){ return json.apply(this, arguments).then(clean); };
        var first;
        Object.defineProperty(window, 'ytInitialPlayerResponse', { configurable: true,
            get: function(){ return first; }, set: function(v){ first = clean(v); } });

        var css = document.createElement('style');
        css.textContent = 'ad-slot-renderer,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,' +
            'ytm-companion-ad-renderer,ytm-banner-promo-renderer,ytm-promoted-sparkles-text-search-renderer,' +
            'ytm-rich-item-renderer:has(ad-slot-renderer),ytm-item-section-renderer:has(ad-slot-renderer),' +
            '.ytp-ad-overlay-container,' +
            // Shorts rafları: giriş yapılmamış tarayıcıya Türkiye'de çok izlenen alakasız videolarla doluyordu
            'ytm-reel-shelf-renderer,ytm-shorts-lockup-view-model,ytm-shorts-lockup-view-model-v2,' +
            'ytm-rich-section-renderer:has(ytm-reel-shelf-renderer),ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model),' +
            'ytm-rich-section-renderer:has(ytm-shorts-lockup-view-model-v2),grid-shelf-view-model:has(ytm-shorts-lockup-view-model),' +
            'ytm-pivot-bar-item-renderer:has(.pivot-shorts){display:none!important}';
        (document.head || document.documentElement).appendChild(css);

        var skipping = false, wasMuted = false;
        setInterval(function(){
            var player = document.querySelector('.html5-video-player');
            var video = player && player.querySelector('video');
            if (!video) return;
            var ad = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');
            if (ad) {
                if (!skipping) wasMuted = video.muted;
                skipping = true;
                video.muted = true;
                video.playbackRate = 16;
                if (isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
                var skip = player.querySelector('.ytp-skip-ad-button,.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-ad-skip-button-container button');
                if (skip) skip.click();
            } else if (skipping) {
                skipping = false;
                video.muted = wasMuted;
                video.playbackRate = 1;
            }
        }, 300);
    })()"""

    /** Daha önce açılmış Kısıtlı Mod'u kapatır (PREF çerezinden f2=8000000 çıkarılır). */
    fun clearRestrictedMode() {
        val cm = android.webkit.CookieManager.getInstance()
        val cur = cm.getCookie("https://m.youtube.com")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=") ?: return
        if (!cur.contains("f2=8000000")) return
        val parts = cur.split('&').filter { it.isNotBlank() && !it.startsWith("f2=") }
        val v = if (parts.isEmpty()) "PREF=; Max-Age=0" else "PREF=${parts.joinToString("&")}; Max-Age=63072000"
        cm.setCookie("https://www.youtube.com", "$v; Domain=.youtube.com; Path=/; Secure")
        cm.flush()
    }
}
