// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import com.newrex.auto.data.Board
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Telefonda fotoğraf seçtiren görünmez ekran. Android'in kendi fotoğraf seçicisi kullanılır; NewREX
 * galerinin tamamına erişmez, sadece seçilen fotoğrafları görür.
 */
class PhotoPickActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Board.init(this)
        if (savedInstanceState != null) return
        val bg = intent.getBooleanExtra(EXTRA_BACKGROUND, false)
        val picker = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").apply {
            if (!bg) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX)
        } else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, !bg)
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(picker, REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST || resultCode != RESULT_OK || data == null) { finish(); return }
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (intent.getBooleanExtra(EXTRA_BACKGROUND, false)) {
            val u = uris.firstOrNull() ?: run { finish(); return }
            scope.launch {
                val ok = com.newrex.auto.data.Background.import(this@PhotoPickActivity, u)
                Toast.makeText(applicationContext, if (ok) t("Arka plan fotoğrafı araç ekranına kondu") else t("Fotoğraf eklenemedi"),
                    Toast.LENGTH_SHORT).show()
                finish()
            }
            return
        }
        scope.launch {
            val n = Board.import(this@PhotoPickActivity, uris.take(MAX))
            Toast.makeText(applicationContext,
                if (n > 0) t("{0} fotoğraf araç ekranına eklendi", n) else t("Fotoğraf eklenemedi"), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST = 51
        private const val MAX = 10
        private const val EXTRA_BACKGROUND = "background"

        /** Araç ekranının arka planı için tek fotoğraf seçtirir. */
        fun startBackground(context: Context) {
            context.startActivity(Intent(context, PhotoPickActivity::class.java).putExtra(EXTRA_BACKGROUND, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        fun start(context: Context) {
            context.startActivity(Intent(context, PhotoPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
