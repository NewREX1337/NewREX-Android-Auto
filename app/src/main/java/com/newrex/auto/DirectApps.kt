// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Telefon uygulamasını ekran yansıtma olmadan doğrudan araç ekranında (NewREX'in sanal ekranında) açar.
 * Android buna normal uygulamalarda izin vermiyor; "shell" yetkisi gerekir. Bu yetki önce NewREX'in kendi
 * yardımcı sürecinden ([SelfAdb], [ShellMain]) alınır; o yoksa ve telefonda Shizuku açıksa Shizuku'dan.
 */
object DirectApps {
    private const val TAG = "NewREX"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    private const val PERMISSION_CODE = 4711

    /** NewREX'in araç ekranının (sanal ekran) numarası; ekran yokken -1. */
    @Volatile var displayId = -1

    /** Araç ekranında şu an açık olan telefon uygulaması (paket, kök görev no); yoksa null. */
    val active = MutableStateFlow<Pair<String, Int>?>(null)

    @Volatile private var service: IShellService? = null
    private var pendingBind: CompletableDeferred<IShellService?>? = null

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false).processNameSuffix("shell").debuggable(false)
            // Yardımcı sürecin kodu değişince sürüm artırılır; yoksa Shizuku eski süreci kullanmaya devam eder
            .version(BuildConfig.VERSION_CODE * 100 + 4)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.takeIf { it.pingBinder() }?.let { IShellService.Stub.asInterface(it) }
            service = s
            pendingBind?.complete(s)
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    // ---- NewREX'in kendi yardımcısı (Shizuku'suz) ----

    @Volatile private var own: IShellService? = null
    private val ownFlow = MutableStateFlow<IShellService?>(null)

    /** [ShellProvider] çağırır: yardımcı süreç kendini teslim etti. */
    fun attachOwn(b: IBinder, version: Int) {
        if (version != ShellMain.VERSION) {
            Log.w(TAG, "Yardımcı sürümü farklı: $version")
            // Eski sürüm yardımcı: kapatılır, yenisi Wi-Fi'de başlatılır
            runCatching { IShellService.Stub.asInterface(b).destroy() }
            return
        }
        val s = IShellService.Stub.asInterface(b)
        runCatching { b.linkToDeath({ if (own?.asBinder() === b) { own = null; ownFlow.value = null; SelfAdb.helperDied() } }, 0) }
        own = s
        ownFlow.value = s
        Log.i(TAG, "NewREX yardımcısı bağlandı")
    }

    /** Kendi yardımcımızla tek bir komut çalıştırır (çıktısı; yardımcı yoksa null). */
    fun execOwn(cmd: String): String? = runCatching { own?.exec(cmd) }.getOrNull()

    fun ownRunning() = own?.asBinder()?.pingBinder() == true

    /** Yardımcı bağlanana kadar bekler (en fazla [ms]). */
    suspend fun awaitOwn(ms: Long): Boolean =
        withTimeoutOrNull(ms) { ownFlow.first { it != null && it.asBinder().pingBinder() } } != null

    /** Telefon uygulamaları araç ekranında açılabilir mi (kendi yardımcımız ya da Shizuku hazır). */
    fun ready() = ownRunning() || granted()

    fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true }.getOrDefault(false)
    fun running() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    /** Shizuku çalışıyor ama izin verilmemişse telefonda izin penceresi açılır; cevabı bekler (izin verildiyse true). */
    suspend fun requestPermission(): Boolean {
        if (granted()) return true
        if (!running()) return false
        val answer = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == PERMISSION_CODE) answer.complete(result == PackageManager.PERMISSION_GRANTED)
        }
        return withContext(Dispatchers.Main) {
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                runCatching { Shizuku.requestPermission(PERMISSION_CODE) }.onFailure { answer.complete(false) }
                withTimeoutOrNull(90_000) { answer.await() } ?: false
            } finally { Shizuku.removeRequestPermissionResultListener(listener) }
        }
    }

    /** Shizuku'nun yardımcı sürecine bağlanır (en fazla birkaç saniye bekler). */
    private suspend fun shell(): IShellService? {
        own?.takeIf { it.asBinder().pingBinder() }?.let { service = it; return it }
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        // Yardımcı bu açılışta başlatılmıştı: NewREX yeni açıldıysa birkaç saniye içinde bağlanır
        appCtx?.let { c -> if (SelfAdb.expected(c) && awaitOwn(3500)) return own.also { service = it } }
        if (!granted()) return null
        val wait = CompletableDeferred<IShellService?>()
        pendingBind = wait
        withContext(Dispatchers.Main) { runCatching { Shizuku.bindUserService(args, connection) }.onFailure { wait.complete(null) } }
        return withTimeoutOrNull(6000) { wait.await() }
    }

    /**
     * Uygulamayı araç ekranında açar. Başarılıysa true; false dönerse (Shizuku yok, uygulama araç ekranına
     * gelmeyi reddetti) çağıran yansıtmaya geçer.
     */
    suspend fun open(ctx: Context, pkg: String): Boolean = withContext(Dispatchers.IO) {
        val d = displayId
        if (d < 0) return@withContext false
        val s = shell() ?: return@withContext false
        appCtx = ctx.applicationContext
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component ?: return@withContext false
        runCatching {
            // Önceki uygulama telefona geri dönsün
            close()
            // Uygulama telefonda zaten açıksa görevi araç ekranına taşınır; değilse araç ekranında başlatılır
            rootTasks(s).firstOrNull { it.pkg == pkg && it.display != d }?.let { s.exec("am display move-stack ${it.id} $d") }
            s.exec("am start --display $d -f 0x10000000 -n ${comp.flattenToShortString()}")
            var task: Task? = null
            for (i in 0 until 10) {
                task = rootTasks(s).firstOrNull { it.pkg == pkg && it.display == d }
                if (task != null) break
                Thread.sleep(150)
            }
            if (task == null) {
                Log.i(TAG, "$pkg araç ekranına gelmedi, yansıtmaya geçiliyor")
                false
            } else {
                active.value = pkg to task.id
                startWatcher()
                true
            }
        }.getOrElse { Log.w(TAG, "Doğrudan açma olmadı", it); false }
    }

    /** Araç ekranındaki uygulamayı telefona geri gönderir (kapanmaz, telefonda kaldığı yerden devam eder). */
    suspend fun close() = withContext(Dispatchers.IO) {
        val (pkg, id) = active.value ?: return@withContext
        active.value = null
        val s = service ?: return@withContext
        if (keepRunning(pkg)) runCatching { s.exec("am display move-stack $id 0") }
        // Oyun gibi arabada işi biten uygulamalar kapatılır: arka planda işlemciyi ve belleği yemesin
        else runCatching { s.exec("am force-stop $pkg") }
    }

    /** Harita, radar, navigasyon ve müzik/podcast uygulamaları araç ekranından çıkınca da çalışmaya devam eder. */
    private var appCtx: Context? = null

    private fun keepRunning(pkg: String): Boolean {
        val p = pkg.lowercase()
        if (KEEP_WORDS.any { p.contains(it) }) return true
        val info = runCatching { appCtx?.packageManager?.getApplicationInfo(pkg, 0) }.getOrNull() ?: return true
        if (Build.VERSION.SDK_INT >= 26) when (info.category) {
            android.content.pm.ApplicationInfo.CATEGORY_MAPS, android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> return true
        }
        return false
    }

    private val KEEP_WORDS = listOf("radar", "navi", "maps", "waze", "yandex", "spotify", "music", "podcast",
        "deezer", "fizy", "tidal", "soundcloud", "audible", "radio")

    // ---- Kilitliyken de açık araç ekranı ----

    /** Araç ekranı Shizuku ile mi oluşturuldu (telefon kilitliyken de uygulamalar görünür)? */
    @Volatile var shellDisplay = false
        private set

    /** Araç ekranı sanal cihazla kuruldu: telefon ekranı kapanınca da uygulamalar araçta çalışmaya devam eder. */
    @Volatile var displayAwake = false
        private set

    /** Shizuku izinliyse araç ekranını shell yetkisiyle kurar; ekran numarası ya da olmazsa null. */
    suspend fun createDisplay(ctx: Context, name: String, w: Int, h: Int, dpi: Int, surface: android.view.Surface): Int? {
        appCtx = ctx.applicationContext
        val s = shell() ?: return null
        val id = withContext(Dispatchers.IO) { runCatching { s.createDisplay(name, w, h, dpi, surface) }.getOrDefault(-1) }
        shellDisplay = id >= 0
        displayAwake = id >= 0 && runCatching { s.displayStaysAwake() }.getOrDefault(false)
        return id.takeIf { it >= 0 }
    }

    fun resizeDisplay(w: Int, h: Int, dpi: Int) { runCatching { service?.resizeDisplay(w, h, dpi) } }
    fun setDisplaySurface(surface: android.view.Surface?) { runCatching { service?.setDisplaySurface(surface) } }
    fun releaseDisplay() {
        if (!shellDisplay) return
        shellDisplay = false
        displayAwake = false
        runCatching { service?.releaseDisplay() }
    }

    /** Araç ekranındaki uygulamaya dokunma gönderir. */
    fun touch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val d = displayId
        if (d < 0) return
        runCatching { service?.touch(action, x, y, downTime, eventTime, d) }
    }

    fun back() {
        val d = displayId
        if (d >= 0) runCatching { service?.key(android.view.KeyEvent.KEYCODE_BACK, d) }
        // Uygulama ilk sayfasındaysa geri tuşu onu kapatır: o zaman NewREX hemen geri gelsin (siyah ekran kalmasın)
        watchScope.launch { kotlinx.coroutines.delay(700); checkAlive() }
    }

    private val watchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private var watcher: kotlinx.coroutines.Job? = null

    /** Araç ekranındaki uygulama kapandıysa (geri tuşu, uygulamanın kendisi) uygulama modundan çıkılır. */
    private fun checkAlive() {
        val (_, id) = active.value ?: return
        val s = service ?: return
        val d = displayId
        val alive = runCatching { rootTasks(s).any { it.id == id && it.display == d } }.getOrDefault(true)
        if (!alive && active.value?.second == id) active.value = null
    }

    /** Uygulama açıkken birkaç saniyede bir hâlâ araç ekranında mı diye bakılır. */
    private fun startWatcher() {
        watcher?.cancel()
        watcher = watchScope.launch {
            while (active.value != null) {
                kotlinx.coroutines.delay(3000)
                checkAlive()
            }
        }
    }

    private class Task(val id: Int, val display: Int, val pkg: String)

    /** "am stack list" çıktısından kök görevler: numara, ekran ve en üstteki uygulamanın paketi. */
    private fun rootTasks(s: IShellService): List<Task> {
        val out = s.exec("am stack list")
        val list = ArrayList<Task>()
        var id = -1
        var display = -1
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("RootTask id=")) {
                id = t.substringAfter("RootTask id=").substringBefore(' ').toIntOrNull() ?: -1
                display = t.substringAfter("displayId=", "").substringBefore(' ').toIntOrNull() ?: -1
            } else if (t.startsWith("taskId=") && id >= 0) {
                val pkg = t.substringAfter(": ").substringBefore('/')
                list += Task(id, display, pkg)
                id = -1
            }
        }
        return list
    }
}
