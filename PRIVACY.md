# Gizlilik — NewREX Android Auto

**Kısaca: NewREX hiçbir veri toplamaz.** Geliştiricinin bir sunucusu yoktur; uygulama hiçbir bilgiyi
geliştiriciye veya başka bir şirkete göndermez.

## Uygulama ne saklar?

Aşağıdakiler **yalnızca senin telefonunda** saklanır ve uygulamayı silince silinir:

- Eklediğin IPTV listelerinin adları ve bağlantıları
- Favori kanalların ve radyoların
- Son çalınanlar listesi (en fazla 30 öğe)
- Seçtiğin tema rengi

Uygulamada **reklam, analiz (analytics), hata raporlama veya takip kütüphanesi yoktur.** Kaynak kod açık
olduğu için bunu herkes doğrulayabilir (`app/build.gradle.kts` içindeki bağımlılık listesine bakabilirsin).

## İzinler ne için?

| İzin | Neden |
|---|---|
| İnternet | Yayınları, radyoları ve web sayfalarını açmak için |
| Konum | **Sadece park modu için**: araç hız bilgisi vermezse hareketi telefonun GPS hızından anlamak. Konum hiçbir yere gönderilmez, kaydedilmez; sadece o anki hız okunur. |
| Araç hızı | Park modu için aracın kendi hız bilgisi |

## Hangi servislere bağlanılır?

Bir şey açtığında telefonun doğrudan o servise bağlanır. Bu servisler, herhangi bir web sitesinde olduğu
gibi, IP adresini görebilir. NewREX bu bağlantılara aracılık etmez ve içeriklerini görmez.

- **YouTube / Google**: YouTube veya Web sayfasını açtığında
- **Radio Browser** (radio-browser.info): radyo listesi ve arama
- **iptv-org** (github.io): varsayılan TV listesi
- Eklediğin IPTV listesinin ve kanalların sunucuları
- Radyo ve kanal logolarının barındırıldığı siteler

## Hesap girişleri

YouTube veya web sitelerine giriş yaparsan oturum bilgileri (çerezler) yalnızca telefonundaki uygulama
içi tarayıcıda saklanır. NewREX şifreni görmez, saklamaz ve hiçbir yere göndermez.
