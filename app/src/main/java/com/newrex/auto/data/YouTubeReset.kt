// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * NewREX'teki YouTube'un çerezlerini ve site verilerini siler: izleme geçmişi ve öneriler sıfırdan başlar.
 * Sadece YouTube/Google adresleri silinir; TV+, Netflix gibi diğer sitelerdeki girişler kalır.
 */
object YouTubeReset {
    private val SITES = listOf(
        "https://www.youtube.com", "https://m.youtube.com", "https://youtube.com", "https://music.youtube.com",
        "https://www.google.com", "https://accounts.google.com", "https://consent.youtube.com",
    )

    fun run() {
        val cm = CookieManager.getInstance()
        for (site in SITES) {
            val host = site.substringAfter("://")
            val root = "." + host.removePrefix("www.").removePrefix("m.").removePrefix("music.")
                .removePrefix("accounts.").removePrefix("consent.")
            val names = cm.getCookie(site)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }.orEmpty()
            for (n in names) {
                // __Secure-/__Host- çerezleri ancak "Secure" ile silinebiliyor
                val gone = "$n=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/; Secure"
                cm.setCookie(site, gone)
                cm.setCookie(site, "$gone; Domain=$root")
                cm.setCookie(site, "$gone; Domain=.$host")
            }
            WebStorage.getInstance().deleteOrigin(site)
        }
        cm.flush()
    }
}
