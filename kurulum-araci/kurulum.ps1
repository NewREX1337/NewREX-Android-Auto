# NewREX Android Auto - Kurulum aracı
# Copyright (C) 2026 NewREX - GPL-3.0-or-later (bkz. NOTICE.md)
# NewREX'i bilgisayardan telefona, Android Auto'nun sürüşte de kabul ettiği kayıtla
# ("Play Store kurdu") kurar. Hiçbir veri toplamaz; sadece bu bilgisayar ile kabloyla
# bağlı telefon arasında çalışır. Yaptığı her şeyi ekrana ve sonuc.txt dosyasına yazar.
$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch {}
try { $Host.UI.RawUI.WindowTitle = 'NewREX Kurulum Aracı' } catch {}
$PKG = 'com.newrex.auto'
$PLAY = 'com.android.vending'
$REPO = 'NewREX1337/NewREX-Android-Auto'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = Join-Path $here 'platform-tools\adb.exe'
$log = Join-Path $here 'sonuc.txt'
$REMOTE = '/sdcard/Download/NewREX-kurulum.apk'
Set-Content $log "NewREX Kurulum Aracı - $(Get-Date -Format 'dd.MM.yyyy HH:mm')" -Encoding UTF8

function Say($t = '', $c = 'White') { Write-Host $t -ForegroundColor $c; Add-Content $log $t -Encoding UTF8 }
function Note($t) { Add-Content $log "    [ayrıntı] $t" -Encoding UTF8 }
function Doing($t) { Say "  ... $t" DarkCyan }
function Ask($t) { Say ''; $r = Read-Host "  >> $t"; Add-Content $log "  >> $t : $r" -Encoding UTF8; if ($null -eq $r) { '' } else { $r.Trim() } }
function Header($n, $title) {
    Clear-Host
    Write-Host '  ==================================================' -ForegroundColor DarkCyan
    Write-Host '           NewREX Android Auto - Kurulum Aracı' -ForegroundColor Cyan
    Write-Host '  ==================================================' -ForegroundColor DarkCyan
    Write-Host ''
    Say "  ADIM $n / 6 :  $title" Yellow
    Write-Host '  --------------------------------------------------' -ForegroundColor DarkGray
    Write-Host ''
}
function Stop-Here($t) {
    Say ''; Say "  SORUN: $t" Red; Say ''
    Say "  Ayrıntılar şu dosyaya yazıldı: $log" Gray
    Say '  Bu dosyayı (veya pencerenin fotoğrafını) geliştiriciye gönderebilirsin.' Gray
    [void](Ask 'Kapatmak için Enter''a bas'); exit 0
}
# Bilgisayara birden fazla cihaz bağlıysa (emülatör vb.) hep seçilen telefonla konuşulur
$script:serial = $null
function Sel { if ($script:serial) { @('-s', $script:serial) } else { @() } }
function Sh($cmd) { $ErrorActionPreference = 'Continue'; $s = Sel; (& $adb @s shell $cmd 2>&1 | ForEach-Object { "$_" } | Out-String).Trim() }
function Adb { $ErrorActionPreference = 'Continue'; $s = Sel; (& $adb @s @args 2>&1 | ForEach-Object { "$_" } | Out-String).Trim() }
function DevState {
    $ErrorActionPreference = 'Continue'
    $rows = (& $adb devices 2>&1 | ForEach-Object { "$_" }) | Where-Object { $_ -match "^(\S+)\t(\S+)" } |
        ForEach-Object { if ($_ -match "^(\S+)\t(\S+)") { [pscustomobject]@{ Id = $Matches[1]; St = $Matches[2] } } }
    $ready = @($rows | Where-Object { $_.St -eq 'device' } | Sort-Object { $_.Id -like 'emulator-*' })
    if ($ready.Count -gt 0) {
        $script:serial = $ready[0].Id
        if ($ready.Count -gt 1) { Note "Birden fazla cihaz: $(($ready | ForEach-Object Id) -join ', '); seçilen: $($script:serial)" }
        return 'ok'
    }
    if ($rows | Where-Object { $_.St -eq 'unauthorized' }) { 'izin' } elseif ($rows | Where-Object { $_.St -eq 'offline' }) { 'offline' } else { 'yok' }
}
function Info {
    $d = Sh "dumpsys package $PKG"
    [pscustomobject]@{
        Kurulu     = $d -match 'versionName='
        Surum      = if ($d -match 'versionName=(\S+)') { $Matches[1] } else { '-' }
        Yukleyen   = if ($d -match 'installerPackageName=(\S+)') { $Matches[1] } else { '-' }
        Baslatan   = if ($d -match 'initiatingPackageName=(\S+)') { $Matches[1] } else { '-' }
        Guncelleme = if ($d -match 'lastUpdateTime=([^\r\n]+)') { $Matches[1].Trim() } else { '' }
    }
}
function Signed($i) { $i.Yukleyen -eq $PLAY -and $i.Baslatan -match 'packageinstaller' }
function Focus { if ((Sh 'dumpsys window') -match 'mCurrentFocus=Window\{\S+ \S+ ([^\s}]+)') { $Matches[1] } else { '?' } }

try {
# ================================================================ 1) Hoş geldin
Header 1 'Başlamadan önce'
Say '  Merhaba! Bu araç NewREX Android Auto''yu telefonuna, arabada sürüş'
Say '  sırasında da açılacak şekilde kurar. Android Auto''nun istediği'
Say '  "Play Store kurdu" kaydını (imzayı) atmaya çalışır ve sonunda'
Say '  başarılı olup olmadığını açıkça yazar.'
Say ''
Say '  Bu pencere sen kapatmadıkça kapanmaz. Her adımda ne yaptığını yazar.' Gray
Say ''
Say '  Telefonunun markası hangisi? Numarasını yazıp Enter''a bas:'
Say ''
Say '    1) Samsung'
Say '    2) Xiaomi / Redmi / POCO'
Say '    3) Realme / Oppo / OnePlus'
Say '    4) Honor / Huawei'
Say '    5) Tecno / Infinix / itel'
Say '    6) Diğer'
$brand = ''
while ($brand -notmatch '^[1-6]$') { $brand = Ask 'Numara' }

# ================================================================ 2) Telefon ayarları
Header 2 'Telefonda geliştirici ayarlarını aç'
Say '  (Daha önce yaptıysan direkt Enter''a bas.)' Gray
Say ''
Say '  A) Geliştirici seçeneklerini aç:' Cyan
switch ($brand) {
    '1' { Say '     Ayarlar > Telefon hakkında > Yazılım bilgileri > "Yapım numarası"na 7 kez dokun.' }
    '2' { Say '     Ayarlar > Telefon hakkında > "İşletim sistemi sürümü" (MIUI sürümü) yazısına 7 kez dokun.' }
    '3' { Say '     Ayarlar > Telefon hakkında > Sürüm > "Yapım numarası"na 7 kez dokun.' }
    default { Say '     Ayarlar > Telefon hakkında > "Yapım numarası"na 7 kez dokun.' }
}
Say '     "Geliştirici oldunuz" yazısı çıkınca tamam.' Gray
Say ''
Say '  B) USB hata ayıklamayı aç:' Cyan
switch ($brand) {
    '1' { Say '     Ayarlar > (en altta) Geliştirici seçenekleri > "USB hata ayıklama" AÇ.' }
    '2' { Say '     Ayarlar > Ek ayarlar > Geliştirici seçenekleri > "USB hata ayıklama" AÇ.' }
    '3' { Say '     Ayarlar > Ek ayarlar (Sistem ayarları) > Geliştirici seçenekleri > "USB hata ayıklama" AÇ.' }
    '4' { Say '     Ayarlar > Sistem ve güncellemeler > Geliştirici seçenekleri > "USB hata ayıklama" AÇ.' }
    default { Say '     Ayarlar > Sistem > Geliştirici seçenekleri > "USB hata ayıklama" AÇ.'
              Say '     (Bulamazsan Ayarlar''daki arama kutusuna "Geliştirici" yaz.)' Gray }
}
if ($brand -eq '2') {
    Say ''
    Say '  C) XIAOMI İÇİN ŞART (aynı sayfada):' Yellow
    Say '     "USB üzerinden yükle" AÇ'
    Say '     "USB hata ayıklama (Güvenlik ayarları)" AÇ'
    Say '     (Mi hesabı ve SIM kart isteyebilir; bunlar açılmazsa Xiaomi kurulumu engeller.)' Gray
}
Say ''
Say '  D) Play Protect''i geçici kapat (kurulumu engellemesin diye):' Cyan
Say '     Play Store > sağ üstte profil resmi > Play Protect > ayarlar (çark) >'
Say '     "Uygulamaları Play Protect ile tara" KAPAT. (İş bitince geri açarsın.)'
if ($brand -eq '1') { Say '     Samsung: Ayarlar > Güvenlik ve gizlilik > Otomatik Engelleyici KAPAT.' }
[void](Ask 'Hepsini yaptıysan Enter''a bas')

# ================================================================ 3) Hazırlık (ADB + APK)
Header 3 'Gerekli dosyalar hazırlanıyor'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = 'SilentlyContinue'
if (-not (Test-Path $adb)) {
    Doing 'Google''ın resmi ADB aracı indiriliyor (bir kereye mahsus, ~10 MB)'
    $zip = Join-Path $here 'platform-tools.zip'
    try {
        Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip -UseBasicParsing
        Expand-Archive $zip -DestinationPath $here -Force
        Remove-Item $zip -Force
    } catch { Stop-Here "ADB indirilemedi, internet bağlantını kontrol et. ($($_.Exception.Message))" }
    Say '  ADB hazır.' Green
} else { Say '  ADB zaten var.' Green }

$apk = Get-ChildItem $here -Filter *.apk -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($apk) {
    Say "  Klasördeki uygulama dosyası kullanılacak: $($apk.Name)" Green
} else {
    Doing 'NewREX''in en son sürümü GitHub''dan indiriliyor'
    try {
        $rel = Invoke-RestMethod "https://api.github.com/repos/$REPO/releases/latest" -UseBasicParsing -Headers @{ 'User-Agent' = 'NewREX-Kurulum' }
        $asset = $rel.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
        if (-not $asset) { throw 'Son sürümde APK dosyası yok' }
        $apkPath = Join-Path $here $asset.name
        Invoke-WebRequest $asset.browser_download_url -OutFile $apkPath -UseBasicParsing
        $apk = Get-Item $apkPath
        Say "  İndirildi: $($asset.name) (sürüm $($rel.tag_name))" Green
    } catch { Stop-Here "Uygulama GitHub'dan indirilemedi. ($($_.Exception.Message)) İstersen APK dosyasını bu klasöre koyup aracı tekrar çalıştır." }
}
Note "APK: $($apk.FullName) ($($apk.Length) bayt)"
Start-Sleep 1

# ================================================================ 4) Telefon bağlantısı
Header 4 'Telefonu kabloyla bilgisayara tak'
[void](Adb start-server)
Say '  1. Telefonun kilidini aç ve USB kablosuyla bilgisayara tak.'
Say '  2. Telefonda "USB kullanımı" sorulursa "Dosya aktarımı"nı seç.'
Say '  3. "USB hata ayıklamaya izin verilsin mi?" çıkınca:'
Say '     "Bu bilgisayara her zaman izin ver"i işaretle > "İzin ver".'
Say ''
Doing 'Telefon bekleniyor (telefon gelene kadar bekler, acele etme)'
$last = ''; $t = 0
while ($true) {
    $s = DevState
    if ($s -eq 'ok') { break }
    if ($s -ne $last -or ($t -gt 0 -and $t % 30 -eq 0)) {
        switch ($s) {
            'izin'    { Say '  -> Telefon görüldü! Telefonun ekranına bak ve "İzin ver"e bas.' Yellow }
            'offline' { Say '  -> Telefon bağlanıyor... Olmazsa kabloyu çıkarıp tekrar tak.' Yellow }
            'yok'     { if ($t -gt 0) {
                          Say '  -> Telefon hâlâ görünmüyor. Kontrol et:' Yellow
                          Say '     - USB hata ayıklama açık mı? (Adım 2-B)'
                          Say '     - Kablo veri taşıyor mu? Bazı kablolar sadece şarj eder, başka kablo dene.'
                          Say '     - Telefonun kilidi açık mı? "İzin ver" penceresi kilit ekranında çıkmaz.' } }
        }
        $last = $s
    }
    Start-Sleep 1; $t++
}
$model = "$(Sh 'getprop ro.product.manufacturer') $(Sh 'getprop ro.product.model')"
$andr = Sh 'getprop ro.build.version.release'
Say ''
Say "  Telefon bağlandı: $model (Android $andr)" Green
Note "Yükleyiciler: $(Sh 'pm list packages packageinstaller')"
$before = Info
if ($before.Kurulu) { Say "  Telefonda NewREX $($before.Surum) kurulu. Kayıt: yükleyen=$($before.Yukleyen), başlatan=$($before.Baslatan)" }
else { Say '  Telefonda NewREX kurulu değil, sıfırdan kurulacak.' }
if (Signed $before) {
    Say ''
    Say '  Bu telefonda imza ZATEN ATILMIŞ, kurulum düzgün!' Green
    $r = Ask 'Yine de yeniden kurmak istersen E yaz, kapatmak için Enter'
    if ($r.ToUpper() -ne 'E') { exit 0 }
}
Start-Sleep 2

# ================================================================ 5) Kurulum
$installer = $null
foreach ($p in 'com.google.android.packageinstaller', 'com.android.packageinstaller') {
    if ((Sh "pm path $p") -match 'package:') { $installer = $p; break }
}
if (-not $installer) { Stop-Here 'Telefonda Android''in paket yükleyicisi bulunamadı.' }
Note "Kullanılan yükleyici: $installer"

function Attempt($n) {
    Header 5 "Uygulama kuruluyor (deneme $n)"
    $pre = Info
    Doing 'Uygulama dosyası telefona kopyalanıyor'
    [void](Sh "rm -f $REMOTE")
    $o = Adb push "$($apk.FullName)" $REMOTE
    Note "push: $o"
    if ($o -notmatch 'pushed|bytes') { Stop-Here "Dosya telefona kopyalanamadı: $o" }
    [void](Sh "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$REMOTE")
    $id = $null
    for ($i = 0; $i -lt 20 -and -not $id; $i++) {
        $q = Sh "content query --uri content://media/external/file --projection _id --where _data\ LIKE\ \'%NewREX-kurulum.apk\'"
        if ($q -match '_id=(\d+)') { $id = $Matches[1] } else { Start-Sleep 1 }
    }
    if (-not $id) { Stop-Here 'Dosya telefonda hazırlanamadı (medya kaydı oluşmadı).' }
    Note "media id: $id"

    Doing 'Telefonda kurulum ekranı "Play Store kurdu" bilgisiyle açılıyor'
    [void](Adb logcat -c)
    [void](Sh 'input keyevent KEYCODE_WAKEUP')
    $o = Sh "am start -f 0x10008000 -a android.intent.action.INSTALL_PACKAGE -p $installer -d content://media/external/file/$id -t application/vnd.android.package-archive --grant-read-uri-permission --es android.intent.extra.INSTALLER_PACKAGE_NAME $PLAY --ez android.intent.extra.NOT_UNKNOWN_SOURCE true"
    Note "am start: $o"
    Start-Sleep 3
    $f = Focus
    Note "Ekrandaki pencere: $f"
    Say ''
    if ($f -match 'packageinstaller') {
        Say '  Telefonda kurulum ekranı açıldı.' Green
    } elseif ($f -match 'miui|securitycenter|oplus|coloros|hihonor|transsion') {
        Say "  Telefonun kendi güvenlik ekranı açıldı ($f)." Yellow
        Say '  Oradaki uyarıyı onayla ("Devam et" / "Yükle" / "İzin ver").' Yellow
    } else {
        Say '  Kurulum ekranı görünmüyor olabilir. Telefonun kilidini açıp ekrana bak.' Yellow
    }
    Say ''
    if ($pre.Kurulu) { Say '  >>> Telefonda "GÜNCELLE" düğmesine bas. <<<' Yellow }
    else { Say '  >>> Telefonda "YÜKLE" düğmesine bas. <<<' Yellow }
    Say '  Play Protect sorarsa: "Yine de yükle" ve "Gönderme".' Gray
    Say ''
    Doing 'Kurulumun bitmesi bekleniyor (en fazla 3 dakika)'
    $post = $pre; $done = $false
    for ($i = 0; $i -lt 180; $i++) {
        Start-Sleep 1
        $post = Info
        if ($post.Kurulu -and ($post.Guncelleme -ne $pre.Guncelleme -or -not $pre.Kurulu)) { $done = $true; break }
        if ($i % 5 -eq 4 -and (Adb logcat -d) -match 'INSTALL_FAILED|Installation failed') { break }
    }
    Start-Sleep 2; $post = Info
    $lc = Adb logcat -d
    $hits = ($lc -split "`n") | Where-Object { $_ -match 'INSTALL_FAILED|InstallStart|PackageInstaller|installer package|miui.*install|SecurityException' } | Select-Object -Last 40
    Note "logcat:`r`n$($hits -join "`r`n")"
    # Yeni (Android 16) kurulum ekranı bilgisayardan gelen imzayı siliyor ama uygulamanın kendi isteğini kabul ediyor
    $why = if ($lc -match 'installer package name \S+ is invalid') { 'yeni' }
           elseif ($lc -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') { 'imza' }
           elseif ($lc -match 'INSTALL_FAILED_VERSION_DOWNGRADE') { 'eski' }
           elseif ($lc -match 'INSTALL_FAILED_USER_RESTRICTED') { 'usb' }
           else { '' }
    [void](Sh "rm -f $REMOTE")
    [pscustomobject]@{ Done = $done; After = $post; Why = $why }
}

function AppMethod {
    Header 5 'İkinci yöntem: NewREX''in kendi düğmesi'
    Say '  Bu telefonun kurulum ekranı bilgisayardan gelen imzayı kabul etmedi.' Yellow
    Say '  Ama aynı imzayı NewREX''in kendi içinden gelirse kabul ediyor. Onu deniyoruz.'
    Say ''
    $pre = Info
    [void](Sh 'input keyevent KEYCODE_WAKEUP')
    [void](Sh "am start -n $PKG/.MainActivity")
    Say '  Telefonda NewREX açıldı. Şimdi telefonda:' Cyan
    Say ''
    Say '   1. Bir pencere çıktıysa "Şimdi yap"a bas.'
    Say '      Pencere çıkmadıysa: aşağı kaydır > "Yardım" > "Arabada görünür yap"a bas.'
    Say '      İzin ekranı açılırsa: NewREX''e izni ver, geri dön ve AYNI DÜĞMEYE BİR DAHA bas.' Yellow
    Say '   2. Açılan kurulum ekranında "GÜNCELLE"ye bas.' Yellow
    Say '   3. Play Protect sorarsa: "Yine de yükle" ve "Gönderme".' Gray
    Say ''
    Doing 'Telefonda güncellemenin bitmesi bekleniyor (en fazla 4 dakika)'
    $post = $pre
    for ($i = 0; $i -lt 240; $i++) {
        Start-Sleep 1
        $post = Info
        if ($post.Guncelleme -ne $pre.Guncelleme) { break }
    }
    Start-Sleep 2
    Info
}

$n = 0
while ($true) {
    $n++
    $res = Attempt $n
    if ($res.Done -and (Signed $res.After)) { break }
    if ($res.Done -and $res.Why -eq 'yeni') {
        Note 'Kurulum ekranı bilgisayar imzasını sildi (yeni yükleyici); uygulama içi yönteme geçiliyor'
        $app = AppMethod
        if (Signed $app) { break }
    }
    Header 5 'Kurulum tamamlanamadı'
    if ($res.Done) {
        Say '  Uygulama kuruldu AMA telefon "Play Store kurdu" kaydını kabul etmedi.' Red
        Say "  Kayıt: yükleyen=$($res.After.Yukleyen), başlatan=$($res.After.Baslatan)" Gray
    } else {
        switch ($res.Why) {
            'imza' { Say '  Telefondaki NewREX başka bir dosyadan kurulmuş; üstüne kurulamıyor.' Red
                     Say '  Çözüm: silip baştan kurmak (aşağıda 2).' Yellow }
            'eski' { Say '  Telefondaki NewREX bu dosyadan daha yeni; eski sürüm üstüne kurulamaz.' Red
                     Say '  Çözüm: silip baştan kurmak (aşağıda 2).' Yellow }
            'usb'  { Say '  Telefon bilgisayardan kurulumu engelledi.' Red
                     if ($brand -eq '2') { Say '  Xiaomi: Geliştirici seçeneklerinde "USB üzerinden yükle"yi aç.' Yellow } }
            default { Say '  Kurulum yapılmadı. "Güncelle/Yükle"ye basılmamış ya da telefon engellemiş olabilir.' Red }
        }
    }
    Say ''
    Say '  Ne yapalım?'
    Say '    1) Tekrar dene'
    Say '    2) Telefondaki NewREX''i silip baştan kur (uygulama ayarları silinir)'
    Say '    3) Bırak, sonucu göster'
    $c = ''
    while ($c -notmatch '^[1-3]$') { $c = Ask 'Numara' }
    if ($c -eq '3') { break }
    if ($c -eq '2') {
        Doing 'Telefondaki NewREX siliniyor'
        Note "uninstall: $(Adb uninstall $PKG)"
        Start-Sleep 2
    }
}

# ================================================================ 6) Sonuç
$final = Info
if ($final.Kurulu) { [void](Sh "am start -n $PKG/.MainActivity") }
Header 6 'Sonuç'
Say "  Telefon : $model (Android $andr)" Gray
$b = if ($before.Kurulu) { "yükleyen=$($before.Yukleyen)  başlatan=$($before.Baslatan)" } else { 'kurulu değildi' }
$a = if ($final.Kurulu) { "NewREX $($final.Surum)  yükleyen=$($final.Yukleyen)  başlatan=$($final.Baslatan)" } else { 'kurulu değil' }
Say "  Önce    : $b" Gray
Say "  Şimdi   : $a" Gray
Say ''
if (Signed $final) {
    Say '  ################################################' Green
    Say '  #   İMZA ATILDI!  Kurulum "Play Store" kaydıyla   #' Green
    Say '  ################################################' Green
    Say ''
    Say '  Son olarak:'
    Say '   1. NewREX telefonda açıldı; bir kez kapat.'
    Say '   2. Play Protect''i (ve Samsung''da Otomatik Engelleyici''yi) geri aç.'
    Say '      "Uygulamayı göndermek ister misin?" diye sorarsa: "Gönderme".'
    Say '   3. Telefonu kablodan çıkar, arabaya bağla. NewREX sürüşte de açılmalı.'
    Say ''
    Say '  NewREX''i her güncellediğinde bu aracı bir kez daha çalıştır.' Gray
} else {
    Say '  ################################################' Red
    Say '  #   İMZA ATILAMADI                                #' Red
    Say '  ################################################' Red
    Say ''
    if ($final.Kurulu) { Say '  NewREX telefonda kurulu ama "Play Store" kaydı yok; arabada park modunda kalır.' }
    Say '  Bu telefon "Play Store kurdu" kaydını engelliyor olabilir.'
}
Say ''
Say "  Bütün adımlar şu dosyaya kaydedildi: $log" Gray
Say '  Geliştiriciye bu dosyayı gönderirsen ne olduğunu tam görebilir.' Gray
[void](Ask 'Kapatmak için Enter''a bas')
} catch {
    Add-Content $log "HATA: $($_ | Out-String)" -Encoding UTF8
    Write-Host ''
    Write-Host "  Beklenmeyen bir sorun oldu: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "  Ayrıntılar: $log" -ForegroundColor Gray
    Write-Host '  Bu dosyayı geliştiriciye gönder.' -ForegroundColor Gray
    Write-Host ''
    [void](Read-Host '  >> Kapatmak için Enter''a bas')
    exit 0
}
