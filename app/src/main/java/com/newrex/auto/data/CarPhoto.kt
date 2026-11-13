// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File

/**
 * Ana ekrandaki araç fotoğrafı (Toyota C-HR 2023-2026). Uygulamaya gömülü değildir: ilk açılışta indirilir,
 * sonra telefonda saklanır. İnternet yoksa kart fotoğrafsız görünür, sayfa her açılışta yeniden dener.
 */
object CarPhoto {
    private const val URL = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEgCKGqs6eKub6dJFyYoCdSyxB3d6BeyZwTOT10oz18nuCVwAFCeDnPzGXkJkgSj1WGQ_By1bYmmlvKC0RCGab3vRBVOARMLNplWbWZ7QEbmIBMpy9yn9SOIdpQx1uUZsK80pmfssibSnUtaDfSxXRccl2EU3gl21VIVnY2_pxTCon2UDHjJO0oc7LQ6/s782/Yeni%20Toyota%20C-HR%20(5).jpg"

    val image = MutableStateFlow<Bitmap?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun load(context: Context) {
        if (image.value != null || job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch { image.value = fetch(app) }
    }

    private fun fetch(context: Context): Bitmap? = runCatching {
        val file = File(context.filesDir, "car_photo/chr.jpg")
        if (!file.exists()) {
            val req = Request.Builder().url(URL).header("User-Agent", Net.USER_AGENT).build()
            Net.client.newCall(req).execute().use { r ->
                val bytes = r.body?.bytes()
                // Sadece gerçek JPEG kaydedilir (sunucu hata sayfası dönebilir)
                if (!r.isSuccessful || bytes == null || bytes.size < 1000 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(file)) return null
            }
        }
        BitmapFactory.decodeFile(file.path)
    }.getOrNull()
}
