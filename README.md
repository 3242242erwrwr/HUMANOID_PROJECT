# HAMROH — jonli va mustaqil suhbatdosh AI

Tayyor gaplarsiz, o'z fikri va xarakteri bor gumanoid hamroh. Eshitadi, ko'radi, sezadi va o'zbekcha gapiradi. Server shart emas — hammasi telefonning o'zida.

## 📥 APK yuklab olish
**[Hamroh.apk ni yuklab olish](https://github.com/3242242erwrwr/HUMANOID_PROJECT/raw/main/APK/Hamroh.apk)**

1. Telefonda yuqoridagi havolani oching va APK ni yuklab oling.
2. O'rnating (kerak bo'lsa "Noma'lum manbalardan o'rnatish"ga ruxsat bering).
3. Ilovani oching → mikrofon, kamera, joylashuv ruxsatlarini bering. Hamroh o'zi uyg'onib, gap boshlaydi.

## Tuzilishi
- **Miya, quloq, ovoz:** `gemini-3.8-live` (zaxira: `gemini-3.1-flash-live-preview`, `gemini-2.5-flash-native-audio-latest`)
- **Ko'z:** CameraX + `gemini-3.1-flash-lite` (yo'l xavfi tahlili)
- **Sezgi:** akselerometr, giroskop, GPS
- **Xotira:** suhbatlar jurnali + `gemini-3.8-flash` orqali uzoq muddatli xotira
- **Jimlik:** 1–1,5 daqiqa jim qolinsa, o'zi gap boshlaydi

Batafsil: [HUMANOID_PROJECT.md](HUMANOID_PROJECT.md)

## O'zingiz yig'ish
`local.properties` ga `GEMINI_API_KEY=...` qo'shing, so'ng: `gradlew assembleDebug`
