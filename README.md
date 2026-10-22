# NewREX Android Auto

**Orijinal geliştirici: NewREX** · Copyright © 2026 NewREX · [GPL-3.0 + ek koşullar](NOTICE.md)

Android Auto için ücretsiz canlı TV, YouTube ve radyo uygulaması.
**Reklam yok, takip yok, hiçbir veri toplanmaz** ([Gizlilik](PRIVACY.md)).
Kodun tamamı bu depoda; içinde ne olduğunu herkes görebilir.

## İndirdiğin dosyanın gerçekten NewREX'ten geldiğini doğrula

Resmi APK'lar yalnızca bu depodaki **Releases** sayfasından yayınlanır ve NewREX'in imza anahtarıyla imzalanır.
Başka bir yerden indirdiysen imzayı kontrol et (bilgisayarda Android SDK ile):

```
apksigner verify --print-certs NewREX-Android-Auto-x.y.z.apk
```

Çıkan `SHA-256` değeri **tam olarak** şu olmalı:

```
8e:bb:a5:7e:25:0c:57:f5:04:1a:14:ca:61:8f:8d:57:99:34:14:8c:54:90:c8:d0:51:24:f9:db:81:dc:ec:8c
```

Farklıysa o dosya NewREX tarafından yapılmamıştır, kurma. Ayrıca telefon, farklı imzalı bir dosyanın kurulu
NewREX'in üzerine güncelleme olarak yüklenmesine izin vermez.

## Özellikler

- 📺 **Canlı TV / IPTV**: M3U/M3U8 listeleri (bağlantı ya da telefondaki dosya), gruplar, arama, favoriler.
  Varsayılan olarak açık kaynak [iptv-org](https://github.com/iptv-org/iptv) Türkiye listesi gelir.
- ▶️ **YouTube**: hızlı kategoriler (Popüler, Müzik, Haber, Spor, Canlı…) ve arama, hesap gerekmez.
- 📻 **İnternet radyosu**: [Radio Browser](https://www.radio-browser.info) üzerinden binlerce Türk radyosu, arama, favoriler.
- 🎙️ **Podcast**: popüler Türkçe podcast'ler, arama, abonelik, kaldığın yerden devam (30 sn ileri / 15 sn geri)
- 📱 **Ekran yansıtma**: telefon ekranı araç ekranında; araçtan dokunma, kaydırma, geri/ana ekran tuşları.
  Her başlatmada telefonda onay istenir. Dokunma için Erişilebilirlik izni gerekir (ekran içeriğini okumaz).
  Netflix gibi korumalı uygulamalar siyah görünür.
- 🚗 **Araç paneli**: hız, yakıt, menzil, kilometre (araç paylaşıyorsa), yolculuk mesafesi ve süresi
- 🔧 **Bakım hatırlatıcı**: muayene, sigorta, kasko, bakım, lastik, MTV; yaklaşınca araç ekranında uyarı
- 🅿️ **Arabam nerede?**: araç kapanınca park yeri kaydedilir, telefondan haritada / yürüyerek yol tarifiyle açılır
- 🌤️ **Hava durumu**: anlık, saatlik ve 5 günlük; yağmur yaklaşınca uyarı
- 🌐 **Web tarayıcı**
- 🅿️ **Park modu**: araç hareket edince video, web ve yansıtma görüntüsü gizlenir, ses devam eder.
  Hız araçtan, alınamazsa telefonun GPS'inden okunur. Ayarlardan kapatılabilir (varsayılan: açık).
- ⭐ Favoriler, son çalınanlar ve "Devam et"
- ⌨️ Araç ekranında Türkçe klavye (büyük harf, semboller, şifre gizleme; sadece park halindeyken).
  Web sayfalarındaki giriş kutularına da yazar, böylece sitelere giriş yapılabilir.
  Not: Google, uygulama içi tarayıcılarda Google/YouTube hesabıyla girişi engeller; YouTube hesapsız kullanılır.
- 🎨 6 tema rengi, dokununca tema renginde neon parlama; dar ekran (bölünmüş görünüm) için sade yerleşim
- Direksiyondaki ileri/geri tuşlarıyla kanal/istasyon değiştirme

## Kurulum (kullanıcı)

1. `NewREX-Android-Auto-x.y.z.apk` dosyasını telefona at ve aç. Telefon "bilinmeyen uygulamaları yükle" izni ister.
   Samsung telefonlarda önce **Ayarlar → Güvenlik ve gizlilik → Otomatik Engelleyici** kapatılmalı.
2. Telefonda **Ayarlar → Bağlı cihazlar → Android Auto** → en alttaki **Sürüm**'e 10 kez dokun
   → sağ üst **⋮ → Geliştirici ayarları** → **Bilinmeyen kaynaklar** kutusunu işaretle.
3. Telefondaki NewREX uygulamasını bir kez açıp **konum iznini** ver (park modu için).
4. Arabada Android Auto'nun uygulama menüsünden **NewREX Android Auto**'yu aç.

> Play Store dışı uygulamalarda Google Play Protect "bilinmeyen geliştirici" uyarısı verebilir.
> Bu, Google'ın kontrol etmediği her uygulamaya verilen standart bir uyarıdır.

## Nasıl çalışıyor?

Google Android Auto'da serbest arayüze izin vermediği için uygulama, resmi
[Car App Library](https://developer.android.com/training/cars/apps)'nin navigasyon şablonunun verdiği çizim yüzeyini
kullanır: yüzeyin üzerinde sanal bir ekran açılır, normal Android arayüzü (kartlar, video oynatıcı, WebView) orada
gösterilir ve araç ekranındaki dokunmalar bu sanal ekrana aktarılır. Bu yüzden uygulama Play Store'a konulamaz ve
Android Auto'da "Bilinmeyen kaynaklar" ayarı gerekir.

| Dosya | Görevi |
|---|---|
| `NewRexCarAppService.kt` | Android Auto bağlantısı, sanal ekran, dokunma aktarımı |
| `ui/CarUi.kt` | Üst bar, mini oynatıcı, sayfa geçişleri, park katmanı |
| `pages/` | Ana ekran, TV, oynatıcı, radyo, YouTube/Web, favoriler, ayarlar |
| `PlayerHub.kt` | Ortak ExoPlayer (TV, radyo, podcast) ve direksiyon tuşları |
| `Mirror.kt` | Ekran yansıtma: onay ekranı, servis, dokunma aktarımı |
| `Vehicle.kt` | Araç bilgileri, park yeri kaydı, hava durumu |
| `DriveState.kt` | Park / sürüş algılama |
| `data/` | Ayarlar, M3U okuyucu, Radio Browser, podcast ve hava durumu istemcileri |
| `MainActivity.kt` | Telefondaki ayar ekranı (IPTV listeleri, izinler) |

## Derleme (geliştirici)

JDK 17 ve Android SDK 35 gerekir.

```
gradlew assembleDebug      # test sürümü
gradlew assembleRelease    # imzalı sürüm (keystore.properties gerekir)
```

İmzalı sürüm için proje köküne `keystore.properties` koy:

```
storeFile=newrex-release.jks
storePassword=...
keyAlias=newrex
keyPassword=...
```

İmza anahtarı (`.jks`) ve `keystore.properties` depoya **eklenmez**. Anahtar kaybolursa aynı uygulamanın
güncellemesi yayınlanamaz; güvenli bir yerde yedekle.

Test sürümünde sürüşü taklit etmek için:

```
adb shell am broadcast -a com.newrex.auto.DEBUG_DRIVE --ez driving true   # sürüşte
adb shell am broadcast -a com.newrex.auto.DEBUG_DRIVE                     # gerçek hıza dön
```

## Güvenlik

- Sürüş sırasında video izlemek tehlikelidir ve trafik kurallarına aykırıdır. Park modu varsayılan olarak açıktır;
  kapatmak kullanıcının sorumluluğundadır.
- Araç tarafı yalnızca Google'ın imzaladığı gerçek Android Auto tarafından açılabilir (test sürümü hariç).
- Uygulama içi tarayıcı telefondaki dosyalara erişemez; Google Güvenli Tarama açıktır.
- Klavyeyle yazılanlar (şifreler dahil) hiçbir yere kaydedilmez; yalnızca o anki web sayfasına aktarılır.

## Lisans

Copyright © 2026 NewREX. Bu program [GNU GPL v3](LICENSE) ile lisanslanmıştır ve şu
[ek koşullar](NOTICE.md) geçerlidir:

1. Değiştirilmiş her sürümün "Hakkında" ekranında **"Orijinal geliştirici: NewREX"** ve bu projenin bağlantısı kalmalıdır;
   kaynak dosyalardaki telif satırları silinemez.
2. Değiştirilmiş sürümler farklı bir ad ve farklı bir paket kimliği kullanmalıdır.
3. "NewREX" adı ve logosu, bu atıf dışında kullanılamaz.

GPL gereği bu kodu kullanan herkes kendi sürümünün kaynak kodunu da aynı lisansla açmak zorundadır.
