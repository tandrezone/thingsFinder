# thingsFinder for Android

Native Kotlin + Jetpack Compose port of the thingsFinder web app. **Offline-first:
everything is stored on the phone** (Room/SQLite); nothing talks to the PHP
server. A backup API is planned — see `../MIGRATION_PLAN.md` §7.

## Open and run

1. Android Studio (Narwhal / 2025.1 or newer) → *Open* → this `android/` folder.
2. Let Gradle sync. If Studio offers AGP / dependency upgrades, accepting them is fine.
3. Run the `app` configuration on an emulator or device (minSdk 24).

No `BASE_URL`, server or login is needed. The only network use is the optional
barcode-name lookup (Open Food Facts / UPCitemdb), which can be turned off in
Settings.

## Layout

```
app/src/main/java/app/thingsfinder/
  domain/        pure Kotlin ports of the PHP rules (slugs, import parser, OCR lines, prompt, box links)
  data/          Room entities/DAOs, InventoryRepository, BarcodeRepository, settings, backup
  lookup/        external barcode-name lookup (OkHttp)
  platform/      ML Kit OCR, Google code scanner, QR (ZXing), label PNG, sharing, file I/O
  ui/            theme (from assets/style.css), navigation, screens + ViewModels, shared components
```

Architecture: single activity · Navigation Compose (type-safe routes) ·
Screen → ViewModel (`StateFlow<UiState>`) → Repository → Room. Manual DI in
`AppContainer`.

Testing: see `TESTING.md`.
