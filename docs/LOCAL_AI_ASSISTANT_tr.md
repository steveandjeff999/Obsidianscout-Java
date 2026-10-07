# Yerel Yapay Zekâ Asistanı

ObsidianScout, küçük yapay zekâ dil modellerini (Qwen2.5) **tamamen tarayıcınızın içinde** çalıştırabilir. Sorularınız, notlarınız ve scouting verileriniz asla bir yapay zekâ hizmetine gönderilmez: sunucu yalnızca model dosyalarını tarayıcınıza verir, geri kalan her şey cihazınızda olur.

Asistan tüm kullanıcılar için **varsayılan olarak kapalıdır**.

## Ne yapar

- **Scouting Asistanı sayfası** (`/assistant`): "Otonomda ilk 5 takım", "254 ve 1678'i karşılaştır", "35. maçı önizle" veya "Kimi seçmeliyiz?" gibi sorular sorun. Tablolar ve grafikler doğrudan verilerinizden hesaplanır. Yazılı yanıtı model üretir ve yanıttaki, verilerinizde bulunamayan her sayının altı çizilir; böylece kontrol edebilirsiniz.
- **Not özetleri**: Takım profil sayfasında ve Nitel Veri sayfasında "Notları özetle" düğmesi, scout'ların yazdıklarını güçlü ve zayıf yönler olarak gruplar.
- **Notu düzenle**: Nitel scouting sırasında "Notu düzenle" düğmesi notun daha temiz bir sürümünü önerir (yazım, kısaltmalar). Kullanıp kullanmamaya siz karar verirsiniz.

Asistan yalnızca hesabınızın zaten görebildiği verileri görür ve hiçbir şeyi değiştiremez.

## Açma

1. **Ayarlar → Kişisel** bölümünü açın ve **Yerel Yapay Zekâ Asistanı**'nı **Açık** yapın.
2. **Bu cihazdaki yapay zekâ modelleri** altında bir model seçip **İndir**'e basın. İndirme her cihazda bir kez yapılır; Wi-Fi kullanın, tercihen etkinlikten önce.
3. Kenar çubuğunda **Asistan** bağlantısı görünür.

Ayarı kapatmak tüm yapay zekâ özelliklerini gizler ve indirilen modelleri cihazdan silmeyi önerir.

## Modeller

| Model | Boyut | Çalıştığı yer | En uygun kullanım |
|---|---|---|---|
| **Lite** (Qwen2.5 0.5B) | ~300 MB (GPU) veya ~520 MB (CPU) | Neredeyse her cihaz; GPU gerekmez (daha yavaş) | Not özetleri ve hızlı sorgular. Yanıtlar doğrudan verilerinizden oluşturulur. |
| **Standart** (Qwen2.5 1.5B) | ~880 MB | WebGPU destekli dizüstüler ve yeni telefonlar | Daha iyi yazılı yanıtlar ve soru anlama |
| **Gemma 4 E2B** (cihaz için QAT) | ~2,5 GB | WebGPU destekli dizüstüler; yeni üst düzey telefonlar (yavaş) | Benzer hızdaki Qwen modellerinden daha iyi akıl yürütme ve araç kullanımı |
| **Gelişmiş** (Qwen2.5 3B) | ~1,75 GB | Güçlü GPU'lu dizüstü ve masaüstü bilgisayarlar | Çok adımlı sorular ve strateji (seçimler, maç planları) |
| **Gemma 4 E4B** (cihaz için QAT) | ~3,5 GB | Güçlü GPU'lu ve 8 GB+ bellekli dizüstü ve masaüstü bilgisayarlar | En yetenekli model: çok adımlı sorular, strateji ve belgeler |

Model seçimi **cihaz bazında** kaydedilir. Cihazınızın çalıştıramadığı modeller nedeniyle birlikte devre dışı gösterilir.

**Tarayıcılar:** Lite dışındaki tüm modeller 16 bit shader destekli WebGPU gerektirir (güncel Chrome veya Edge, Safari 26+). Lite WebGPU olmadan da CPU'da çalışır. Gemma 4 modelleri Qwen modellerinden daha yavaş yazar ama daha iyi akıl yürütür.

**Sorun giderme:** Yanıtlar bozuk çıkarsa veya sayfa çökerse model panelinde "GPU'yu kullanma" seçeneğini işaretleyin ya da modeli silip yeniden indirin.

## Sunucu yöneticileri için

Modeller sunucuya bir kez yüklenir, ardından tarayıcılar onları sizin sunucunuzdan indirir (`/models/...`). Kullanım sırasında üçüncü taraf sitelerden hiçbir şey indirilmez.

- **Web sitesinden:** Depolama Yöneticisi (site yöneticisi) → **Yerel yapay zekâ modelleri** → **Yükle**. İlerleme canlı gösterilir; yarıda kalan indirmeler devam eder ve her dosya SHA-256 özetiyle doğrulanır.
- **Komut satırından:** `obsidianscout-server --install-ai-models lite,standard,advanced`.
- Dosyalar `data/models/` klasöründe saklanır. Başka bir klasör için `OBSIDIANSCOUT_MODELS_DIR` kullanın. Beş model yaklaşık 9,5 GB yer kaplar; kurulum, boş alanı `local_ai.min_free_disk_mb` (varsayılan 2048 MB) altına düşürecekse reddedilir.
- Kümede, kullanıcılara hizmet veren her düğüme modelleri yükleyin.
- **Otomatik kurulum:** `config/app-config.json` içinde listelenenler dışında başlangıçta hiçbir şey indirilmez, ör. `"local_ai": { "auto_install_tiers": ["lite"] }`.
- **Otomatik temizlik:** sunucu her başlangıçta artık kullanılmayan model dosyalarını siler; tarayıcılar önbellekteki kopyaları siteyi bir sonraki açışta siler.

**Lisanslar:** Qwen2.5 0.5B ve 1.5B ile Gemma 4 E2B/E4B Apache-2.0'dır. Qwen2.5 3B, Qwen Research License (ticari olmayan kullanım) kapsamındadır.
