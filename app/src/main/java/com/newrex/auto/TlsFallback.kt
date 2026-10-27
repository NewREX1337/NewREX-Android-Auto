// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Bazı yerel kanalların sunucu sertifikası bozuk (süresi geçmiş / yanlış ad); https ile hiç açılmıyorlar ama
 * aynı adres http ile çalışıyor. Sadece herkese açık TV yayınları için: sertifika hatası veren sunucuya
 * bir kez http ile bağlanılır ve o sunucu bu oturumda hep http ile açılır. Şifre/hesap içeren sayfalar
 * (TV+, Netflix, YouTube) bu yoldan geçmez, onlar tarayıcıda açılır.
 */
@UnstableApi
class TlsFallbackFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
    override fun createDataSource(): DataSource = TlsFallbackSource(upstream.createDataSource())

    private class TlsFallbackSource(private val inner: DataSource) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

        override fun open(given: DataSpec): Long {
            // "http://…:443" aslında şifreli bağlantı bekler (ör. yönlendirilen yayın listeleri)
            val dataSpec = if (given.uri.scheme == "http" && given.uri.port == 443)
                given.withUri(given.uri.buildUpon().scheme("https").encodedAuthority(given.uri.host).build()) else given
            val host = dataSpec.uri.host
            if (dataSpec.uri.scheme == "https" && host != null && host in broken) return inner.open(plain(dataSpec))
            return try {
                inner.open(dataSpec)
            } catch (e: IOException) {
                val tls = generateSequence(e as Throwable) { it.cause }.take(8)
                    .any { it is javax.net.ssl.SSLException || it is java.security.cert.CertificateException }
                if (!tls || dataSpec.uri.scheme != "https" || host == null) throw e
                broken += host
                runCatching { inner.close() }
                inner.open(plain(dataSpec))
            }
        }

        private fun plain(spec: DataSpec) = spec.withUri(spec.uri.buildUpon().scheme("http").build())

        override fun read(buffer: ByteArray, offset: Int, length: Int) = inner.read(buffer, offset, length)
        override fun getUri(): Uri? = inner.uri
        override fun getResponseHeaders() = inner.responseHeaders
        override fun close() = inner.close()
    }

    companion object {
        private val broken: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
