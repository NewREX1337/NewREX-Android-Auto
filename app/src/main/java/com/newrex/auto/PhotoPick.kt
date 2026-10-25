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
        val picker = if (Build.VERSION.SDK_INT >= 33)
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*").putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MAX)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
        scope.launch {
            val n = Board.import(this@PhotoPickActivity, uris.take(MAX))
            Toast.makeText(applicationContext,
                if (n > 0) "$n fotoğraf araç ekranına eklendi" else "Fotoğraf eklenemedi", Toast.LENGTH_SHORT).show()
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

        fun start(context: Context) {
            context.startActivity(Intent(context, PhotoPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
