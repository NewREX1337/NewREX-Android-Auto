// NewREX Android Auto — Copyright (C) 2026 NewREX — GPL-3.0-or-later (bkz. NOTICE.md)
package com.newrex.auto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import java.io.File
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku'suz yardımcı süreç: NewREX telefonun kendi "Kablosuz hata ayıklama" özelliğine telefonun içinden
 * bağlanır ve [ShellMain]'i başlatır. Bir kez eşleştirme kodu girilir; sonra telefon her açılıp Wi-Fi'ye
 * bağlandığında kendiliğinden başlar (bunun için bilgisayardan bir kez "güvenli ayar" izni verilmiş olmalı).
 * Bağlantı sadece telefonun içindedir; anahtar telefonda kalır, hiçbir yere bir şey gönderilmez.
 */
object SelfAdb {
    private const val TAG = "NewREX"
    private const val CHANNEL = "selfadb"
    private const val NOTIF_ID = 7301
    private const val JOB_ID = 7302
    const val KEY_CODE = "code"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private fun prefs(c: Context) = c.getSharedPreferences("selfadb", Context.MODE_PRIVATE)

    /** Bir kez eşleştirildi mi. */
    fun paired(c: Context) = prefs(c).getBoolean("paired", false)

    /** Kablosuz hata ayıklamayı NewREX kendisi açıp kapatabilir mi (bilgisayardan bir kez verilen izin). */
    fun canToggle(c: Context) = c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Bu açılışta yardımcı başlatıldı mı (NewREX yeniden açılınca birkaç saniye içinde bağlanır). */
    fun expected(c: Context): Boolean {
        val p = prefs(c)
        return p.getInt("boot", -1) == bootCount(c) && p.getLong("apk", 0) == apkStamp(c)
    }

    private fun bootCount(c: Context) = Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    private fun apkStamp(c: Context) = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    // ---- Anahtar ----

    private class Manager(private val c: Context) : AbsAdbConnectionManager() {
        private val key: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(10, TimeUnit.SECONDS)
            val dir = File(c.noBackupFilesDir, "adb").apply { mkdirs() }
            val kf = File(dir, "key"); val cf = File(dir, "cert")
            if (!kf.exists() || !cf.exists()) generate(kf, cf)
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.readBytes().inputStream())
        }

        private fun generate(kf: File, cf: File) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=NewREX")
            val alg = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val now = System.currentTimeMillis()
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(now))
                setIssuer(name); setSubject(name)
                setStartDate(Time(Date(now - 86_400_000L)))
                setEndDate(Time(Date(now + 30L * 365 * 86_400_000L)))
                setSignature(alg)
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(kp.public.encoded))
            }.generateTBSCertificate()
            val sig = Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update(tbs.encoded) }.sign()
            val der = DERSequence(ASN1EncodableVector().apply { add(tbs); add(alg); add(DERBitString(sig)) }).encoded
            kf.writeBytes(kp.private.encoded)
            cf.writeBytes(der)
        }

        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "NewREX"
    }

    @Volatile private var manager: Manager? = null
    private fun manager(c: Context) = manager ?: Manager(c.applicationContext).also { manager = it }

    // ---- Eşleştirme (bir kez) ----

    /** Kablosuz hata ayıklama ayarını açar ve kodun yazılacağı bildirimi gösterir. */
    fun beginPairing(c: Context) {
        if (canToggle(c)) runCatching { Settings.Global.putInt(c.contentResolver, "adb_wifi_enabled", 1) }
        showPairNotification(c, t("Kablosuz hata ayıklama → “Eşleme kodu ile cihaz eşle”ye bas, sonra bu bildirimde “Kodu yaz”a basıp 6 haneli kodu yaz."))
        runCatching {
            c.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun showPairNotification(c: Context, text: String, input: Boolean = true) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Uygulamaları araçta açma"), NotificationManager.IMPORTANCE_HIGH))
        val b = Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_car)
            .setContentTitle(t("NewREX eşleştirme"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setAutoCancel(!input)
        if (input) {
            val pi = PendingIntent.getBroadcast(c, 1, Intent(c, PairReplyReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            b.addAction(Notification.Action.Builder(null, t("Kodu yaz"), pi)
                .addRemoteInput(RemoteInput.Builder(KEY_CODE).setLabel(t("6 haneli kod")).build()).build())
        }
        nm.notify(NOTIF_ID, b.build())
    }

    /** Eşleştirme penceresindeki kodla eşleşir; olursa yardımcıyı hemen başlatır. */
    fun pair(c: Context, code: String, done: (Boolean) -> Unit = {}) {
        val app = c.applicationContext
        scope.launch {
            showPairNotification(app, t("Eşleştiriliyor…"), input = false)
            val ok = runCatching {
                val port = discover(app, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 8000) ?: error("eşleştirme kapısı bulunamadı")
                manager(app).pair("127.0.0.1", port, code.trim())
            }.onFailure { Log.w(TAG, "Eşleştirme olmadı", it) }.getOrDefault(false)
            if (ok) {
                prefs(app).edit().putBoolean("paired", true).apply()
                val started = start(app)
                showPairNotification(app, if (started) t("Eşleşti. Telefon uygulamaları artık araç ekranında açılabilir.")
                    else t("Eşleşti. Yardımcı birazdan kendiliğinden başlayacak."), input = false)
            } else showPairNotification(app, t("Kod tutmadı. Eşleştirme penceresi açıkken yeni kodu tekrar yaz."))
            withContext(Dispatchers.Main) { done(ok) }
        }
    }

    private fun discover(c: Context, type: String, timeoutMs: Long): Int? {
        var port = -1
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(c, type) { host: InetAddress?, p: Int -> if (host != null && p > 0) { port = p; latch.countDown() } }
        mdns.start()
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { mdns.stop() }
        return port.takeIf { it > 0 }
    }

    // ---- Başlatma ----

    /**
     * Yardımcı süreci başlatır. Wi-Fi'de olmak ve eşleştirilmiş olmak gerekir. Kablosuz hata ayıklama kapalıysa
     * (izin varsa) kısa süreliğine açılır, iş bitince eski haline döner. Yardımcı telefon kapanana kadar çalışır.
     */
    suspend fun start(c: Context): Boolean = withContext(Dispatchers.IO) {
        val app = c.applicationContext
        if (!paired(app)) return@withContext false
        if (DirectApps.ownRunning()) return@withContext true
        lock.withLock {
            if (DirectApps.ownRunning()) return@withLock true
            val cr = app.contentResolver
            val wasOn = Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1
            if (!wasOn && !canToggle(app)) return@withLock false
            if (!wasOn) runCatching { Settings.Global.putInt(cr, "adb_wifi_enabled", 1) }
            val m = manager(app)
            try {
                if (!m.connectTls(app, 10_000)) return@withLock false
                val apk = app.applicationInfo.sourceDir
                val pkg = app.packageName
                // Eski yardımcı varsa kapatılır; yenisi oturumdan bağımsız çalışır (bağlantı kapanınca ölmez)
                val cmd = "kill $(pidof 'newrex_shell:$pkg') 2>/dev/null ; CLASSPATH='$apk' setsid nohup app_process /system/bin " +
                    "--nice-name='newrex_shell:$pkg' com.newrex.auto.ShellMain '$pkg' '$apk' </dev/null >/dev/null 2>&1 & sleep 2; echo NEWREX_OK"
                m.openStream("shell:$cmd").use { s ->
                    // Komutun çalışması için kısa bir süre bekle, sonra bağlantıyı kapat
                    val inp = s.openInputStream()
                    val buf = ByteArray(256)
                    val until = System.currentTimeMillis() + 6000
                    while (!s.isClosed && System.currentTimeMillis() < until) {
                        if (inp.available() > 0) inp.read(buf) else Thread.sleep(100)
                    }
                }
                val ok = DirectApps.awaitOwn(8000)
                // Yardımcı, NewREX'e otomatik başlama iznini kendisi verir (bilgisayar gerekmez)
                if (ok && !canToggle(app)) DirectApps.execOwn("pm grant $pkg ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                if (ok) prefs(app).edit().putInt("boot", bootCount(app)).putLong("apk", apkStamp(app)).apply()
                Log.i(TAG, "NewREX yardımcısı ${if (ok) "başladı" else "başlamadı"}")
                ok
            } catch (e: Throwable) {
                Log.w(TAG, "NewREX yardımcısı başlatılamadı", e)
                if (e is io.github.muntashirakon.adb.AdbPairingRequiredException) prefs(app).edit().putBoolean("paired", false).apply()
                false
            } finally {
                runCatching { m.disconnect() }
                if (!wasOn) runCatching { Settings.Global.putInt(cr, "adb_wifi_enabled", 0) }
            }
        }
    }

    @Volatile private var appCtx: Context? = null

    /** NewREX açıldı: yardımcı birkaç saniye içinde bağlanmazsa (telefon yeni açıldı, yardımcı kapandı) Wi-Fi'de başlatılır. */
    fun init(c: Context) {
        val app = c.applicationContext
        appCtx = app
        if (!paired(app)) return
        scope.launch {
            kotlinx.coroutines.delay(if (expected(app)) 7000 else 1500)
            if (!DirectApps.ownRunning()) { forget(app); schedule(app) }
        }
    }

    /** Yardımcı kapandı: bir dahaki Wi-Fi'de yeniden başlatılsın. */
    fun helperDied() {
        val app = appCtx ?: return
        forget(app)
        schedule(app)
    }

    private fun forget(c: Context) = prefs(c).edit().remove("boot").apply()

    /** Telefon açılınca / NewREX güncellenince: Wi-Fi'ye bağlanıldığı ilk anda yardımcıyı başlatacak iş. */
    fun schedule(c: Context) {
        if (!paired(c) || expected(c)) return
        val js = c.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(JOB_ID) != null) return
        js.schedule(JobInfo.Builder(JOB_ID, ComponentName(c, StartJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build())
    }

    class StartJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            scope.launch {
                val ok = runCatching { start(applicationContext) }.getOrDefault(false)
                jobFinished(params, !ok && paired(applicationContext))
            }
            return true
        }
        override fun onStopJob(params: JobParameters) = true
    }

    /** Telefon açıldı ya da NewREX güncellendi. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = schedule(c)
    }

    /** Bildirime yazılan eşleştirme kodu. */
    class PairReplyReceiver : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val code = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_CODE)?.toString()?.filter { it.isDigit() }
            if (code.isNullOrEmpty()) return
            val pending = goAsync()
            pair(c, code) { pending.finish() }
        }
    }
}
