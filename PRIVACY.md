# Gizlilik — NewREX Android Auto

**Kısaca: NewREX hiçbir veri toplamaz.** Geliştiricinin bir sunucusu yoktur; uygulama hiçbir bilgiyi
geliştiriciye veya başka bir şirkete göndermez.

## Uygulama ne saklar?

Aşağıdakiler **yalnızca senin telefonunda** saklanır ve uygulamayı silince silinir:

- Eklediğin IPTV listelerinin adları ve bağlantıları
- Favori kanalların, radyoların ve podcast bölümlerin
- Son çalınanlar listesi (en fazla 30 öğe)
- Podcast abonelikleri ve bölümlerde kaldığın yer
- Seçtiğin tema rengi ve park modu ayarı
- Son park yeri (araç kapanınca kaydedilen tek bir konum; istediğin an silebilirsin)
- Bakım hatırlatıcıya girdiğin tarihler

Uygulamada **reklam, analiz (analytics), hata raporlama veya takip kütüphanesi yoktur.** Kaynak kod açık
olduğu için bunu herkes doğrulayabilir (`app/build.gradle.kts` içindeki bağımlılık listesine bakabilirsin).

## İzinler ne için?

| İzin | Neden |
|---|---|
| İnternet | Yayınları, radyoları, podcast'leri, hava durumunu ve web sayfalarını açmak için |
| Konum | Park modu (araç hız vermezse GPS hızı), park yerini kaydetme, hava durumu ve yolculuk mesafesi. Konum geçmişi tutulmaz; sadece son park yeri telefonda saklanır. |
| Araç hızı, yakıt, kilometre | Park modu ve araç paneli. Her araç bu bilgileri paylaşmaz. Telefondan dışarı çıkmaz. |
| Bildirim | Ekran yansıtma sürerken Android'in zorunlu tuttuğu "yansıtılıyor" bildirimi |
| Ekran paylaşımı | **Sadece sen başlatırsan**: telefon ekranını araç ekranında göstermek. Her seferinde telefonda onay istenir. Görüntü yalnızca araç ekranına çizilir; kaydedilmez, hiçbir yere gönderilmez. |

## Hangi servislere bağlanılır?

Bir şey açtığında telefonun doğrudan o servise bağlanır. Bu servisler, herhangi bir web sitesinde olduğu
gibi, IP adresini görebilir. NewREX bu bağlantılara aracılık etmez ve içeriklerini görmez.

- **YouTube / Google**: YouTube veya Web sayfasını açtığında
- **Radio Browser** (radio-browser.info): radyo listesi ve arama
- **Apple Podcasts dizini** (itunes.apple.com): podcast listesi ve arama (aradığın kelime bu servise gider)
- Podcast programlarının kendi sunucuları: bölüm listesi ve ses dosyaları
- **Open-Meteo** (open-meteo.com): hava durumu. Konumun yaklaşık **1 km'ye yuvarlanarak** gönderilir, tam yerin gönderilmez.
- Telefonun yer adı servisi (Android Geocoder): hava durumunda il/ilçe adını göstermek için
- **iptv-org** (github.io): varsayılan TV listesi
- Eklediğin IPTV listesinin ve kanalların sunucuları
- Radyo, kanal ve podcast görsellerinin barındırıldığı siteler

## Hesap girişleri

YouTube veya web sitelerine giriş yaparsan oturum bilgileri (çerezler) yalnızca telefonundaki uygulama
içi tarayıcıda saklanır. NewREX şifreni görmez, saklamaz ve hiçbir yere göndermez.
