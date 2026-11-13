// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.newrex.auto.data.CarImage
import com.newrex.auto.ui.MATCH
import com.newrex.auto.ui.Palette
import com.newrex.auto.ui.WRAP
import com.newrex.auto.ui.dp
import com.newrex.auto.ui.label
import com.newrex.auto.ui.lp
import com.newrex.auto.ui.margins
import com.newrex.auto.ui.pill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Telefonda araç görseli seçme ekranı: görsel seçilir, arka planı otomatik silinir, göstergedeki gibi koyu zeminde
 * önizlenir; beğenilirse araç ekranına konur. Arka plan silme Google'ın telefonda çalışan modeliyle yapılır,
 * görsel hiçbir yere gönderilmez.
 */
class CarImageActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val segmenter by lazy {
        SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
    }

    private var original: Bitmap? = null
    private var cut: Bitmap? = null
    private var removeBg = true

    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var modes: LinearLayout
    private lateinit var saveBtn: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.newrex.auto.data.Board.init(this)
        setContentView(build())
        if (savedInstanceState == null) pick()
    }

    private fun build(): View {
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        status = label("", 15f, Palette.TEXT_DIM, lines = 3).apply { gravity = Gravity.CENTER }
        modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        saveBtn = pill(t("Araç ekranına koy"), R.drawable.ic_check, selected = true, heightDp = 52) { save() }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0D12.toInt()); fitsSystemWindows = true }
        return root.also { it.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(label(t("Araç görseli"), 22f, bold = true), lp(MATCH, WRAP).margins(b = dp(4)))
            addView(label(t("Göstergede böyle görünecek."), 14f, Palette.TEXT_DIM), lp(MATCH, WRAP).margins(b = dp(12)))
            // Göstergenin ortasına benzeyen koyu zemin; altta tema renginde ışık
            addView(FrameLayout(this@CarImageActivity).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT
                    gradientRadius = resources.displayMetrics.widthPixels * 0.7f
                    setGradientCenter(0.5f, 0.85f)
                    colors = intArrayOf(Palette.withAlpha(Palette.accent, 70), 0xFF14171E.toInt(), 0xFF07080B.toInt())
                    cornerRadius = dp(18).toFloat()
                }
                addView(preview, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
            }, lp(MATCH, 0, 1f))
            addView(status, lp(MATCH, WRAP).margins(t = dp(12), b = dp(10)))
            addView(modes, lp(MATCH, WRAP).margins(b = dp(12)))
            addView(LinearLayout(this@CarImageActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(pill(t("Başka görsel seç"), R.drawable.ic_photo, heightDp = 52) { pick() }, lp(WRAP, dp(52)).margins(r = dp(10)))
                addView(saveBtn, lp(WRAP, dp(52)))
            }, lp(MATCH, WRAP))
            addView(pill(t("Vazgeç"), R.drawable.ic_close, heightDp = 44) { finish() },
                lp(WRAP, dp(44)).margins(t = dp(10)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }, FrameLayout.LayoutParams(MATCH, MATCH)) }
    }

    private fun pick() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(i, REQUEST) }.onFailure { finish() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { if (original == null) finish(); return }
        original = null; cut = null
        refresh()
        status.text = t("Görsel açılıyor…")
        scope.launch {
            val bmp = CarImage.decode(this@CarImageActivity, uri)
            if (bmp == null) { status.text = t("Fotoğraf eklenemedi"); return@launch }
            original = bmp
            removeBg = true
            refresh()
            segment()
        }
    }

    /** Arka planı sil: model telefonda yoksa önce (bir kereliğine) indirilir. */
    private fun segment() {
        val src = original ?: return
        status.text = t("Arka plan siliniyor…")
        val mi = ModuleInstall.getClient(this)
        mi.areModulesAvailable(segmenter).addOnSuccessListener { r ->
            if (r.areModulesAvailable()) run(src)
            else {
                status.text = t("Arka plan silme aracı indiriliyor (sadece ilk sefer)…")
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(u: ModuleInstallStatusUpdate) {
                        when (u.installState) {
                            ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> { mi.unregisterListener(this); run(src) }
                            ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                            ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> { mi.unregisterListener(this); failed() }
                        }
                    }
                }
                mi.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener).build())
                    .addOnSuccessListener { if (it.areModulesAlreadyInstalled()) run(src) }
                    .addOnFailureListener { failed() }
            }
        }.addOnFailureListener { failed() }
    }

    private fun run(src: Bitmap) {
        segmenter.process(InputImage.fromBitmap(src, 0)).addOnSuccessListener { res ->
            if (original !== src) return@addOnSuccessListener
            val fg = res.foregroundBitmap
            scope.launch {
                cut = fg?.let { withContext(Dispatchers.Default) { CarImage.trim(it) } }
                if (cut == null) { removeBg = false; status.text = t("Görselde araç bulunamadı, olduğu gibi kullanılabilir.") }
                refresh()
            }
        }.addOnFailureListener { if (original === src) failed() }
    }

    private fun failed() {
        removeBg = false
        status.text = t("Arka plan silinemedi. Görseli olduğu gibi kullanabilir ya da arka planı şeffaf bir PNG seçebilirsin.")
        refresh()
    }

    private fun refresh() {
        val shown = if (removeBg && cut != null) cut else original
        preview.setImageBitmap(shown)
        saveBtn.alpha = if (shown != null) 1f else 0.4f
        saveBtn.isEnabled = shown != null
        modes.removeAllViews()
        if (original == null) return
        if (cut != null) {
            status.text = if (removeBg) t("Arka plan silindi. Kenarlar düzgün değilse “Olduğu gibi”yi seç.") else t("Görsel olduğu gibi.")
            modes.addView(pill(t("Arka planı sil"), selected = removeBg, heightDp = 44) { removeBg = true; refresh() },
                lp(WRAP, dp(44)).margins(r = dp(10)))
            modes.addView(pill(t("Olduğu gibi"), selected = !removeBg, heightDp = 44) { removeBg = false; refresh() }, lp(WRAP, dp(44)))
        }
    }

    private fun save() {
        val bmp = (if (removeBg && cut != null) cut else original) ?: return
        saveBtn.isEnabled = false
        scope.launch {
            val ok = CarImage.save(applicationContext, bmp)
            Toast.makeText(applicationContext, if (ok) t("Araç görseli araç ekranına kondu") else t("Fotoğraf eklenemedi"),
                Toast.LENGTH_SHORT).show()
            if (ok) finish() else saveBtn.isEnabled = true
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { segmenter.close() }
        super.onDestroy()
    }

    companion object {
        private const val REQUEST = 52

        fun start(context: Context) {
            context.startActivity(Intent(context, CarImageActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
