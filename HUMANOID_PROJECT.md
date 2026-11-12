# HUMANOID AGENT — JONLI VA MUSTAQIL SUHBATDOSH AI

> **Loyiha maqsadi:** Alisa, Siri yoki oddiy shablonli botlar kabi emas! Tayyor yozilgan so'zlar (prisetlar, shablonlar) UMUMAN BO'LMAGAN, o'zining mustaqil fikri, xarakteri, his-tuyg'usi va kuzatuvchanligi bor tirik gumanoid suhbatdosh yaratish.

---

## 1. BOSH VA O'ZGARIShSIZ QONUN (TABU)
1. **HECH QANDAY TAYYOR GAP YO'Q:** Dasturchi kodga oldindan birorta ham gap yoki matn yozib qo'yishi qat'iyan taqiqlanadi!
2. **HAR BIR GAP O'SHA LAHZADA TUG'ILADI:** AI har bir so'zni o'sha soniyadagi real vaziyat (vaqt, tezlik, sokinlik, kayfiyat, ob-havo)ga qarab o'z miyasida (Gemini 2.5 Flash) no'ldan tuzadi.
3. **QO'L TEGMAYDI (NO BUTTONS):** Inson birorta tugma ezmaydi. Tizim 100% insondek eshitadi, ko'radi, sezadi va gapiradi.
4. **SHAXSIYAT VA SUHBATDOSH:** U faqat savolga javob beruvchi robot emas. U — o'zi mustaqil gap boshlay oladigan, fikr bildiradigan, hazillashadigan va qayg'uradigan tirik hamroh.

---

## 2. GUMANOIDNING 4 TA AZOSI (ARXITEKTURA)

```
       [ KO'Z ]                    [ QULOQ ]                   [ SEZGI (ASAB) ]
  Telefon Kamerasi            Doimiy Ochiq Mikrofon        Akselerometr & GPS Tezlik
 (yo'l, mashina, svetafor)     (inson so'zi, nafas, fon)   (zarba, chuqur, tezlanish)
         │                               │                            │
         └───────────────────────┬───────┴────────────────────────────┘
                                 ▼
                         [ MIYA (AKL) ]
                      Gemini 2.5 Live / Flash
               (Vaziyatni insondek his qiladi,
                mustaqil fikr va munosabat tuzadi)
                                 │
                                 ▼
                         [ TIL VA OVOZ ]
                     Gemini O'zbekcha TTS
               (Dinamikdan xuddi tirik insondek
                samimiy, o'zbekona lutf bilan yangraydi)
```

---

## 3. REAL HAYOTIY XULQ-ATVORI (BEHAVIOR)

### A. Avtomobilda (Rulda):
- **O'zi gap boshlashi:** Agar haydovchi (Jamshid aka) 1-1,5 daqiqa jim ketsa, robot vaziyatga qarab o'zi sokin mavzu topadi yoki kayfiyatini so'raydi.
- **Ko'z orqali ko'rishi:** Oldindagi xavf, kutilmagan tormoz yoki svetaforni ko'rib insoniy munosabat bildiradi.
- **Sezgi orqali his qilishi:** Chuqurga tushganda yoki keskin burilganda o'ziga xos jonli reaktsiya beradi (hech qachon bir xil emas).

### B. Muloqot uslubi (Shaxsiyat):
- **O'zbekona hurmat va mehr:** "Jamshid aka", "aka", samimiylik, andijoncha/farg'onacha ochiqko'ngillik.
- **Xotira (Memory):** Kecha nima gaplashilgani, qayerga borilgani va nimalar reja qilinganini unutmaydi.

---

## 4. TEXNOLOGIYALAR STACKI
- **Dvigatel (AI Engine):** Gemini 2.5 Flash / Gemini Live API (Ultra-past kechikish, real-time audio).
- **Audio kirish (Input):** Android AudioRecord (16kHz PCM, fonda uzluksiz eshitish).
- **Ko'rish (Vision):** CameraX (har bir necha soniyada yo'l kadrlari tahlili).
- **Asab tizimi:** Android SensorManager (TYPE_ACCELEROMETER, TYPE_GYROSCOPE, LocationManager).
- **Platforma:** To'liq mustaqil Android ilova — alohida server YO'Q. Server vazifalarini (Gemini bilan aloqa, xotira saqlash va jamlash, ob-havo, qayta ulanish) telefonning o'zi fon xizmati (Foreground Service) sifatida bajaradi.

---

*Hujjat Jamshid aka bilan kelishilgan holda yaratildi. Yangi loyiha ushbu hujjat asosida no'ldan va mutlaqo toza holda boshlanadi.*
