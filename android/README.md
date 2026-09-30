# thingsFinder for Android

Native Kotlin + Jetpack Compose port of the thingsFinder web app. **Offline-first:
everything is stored on the phone** (Room/SQLite) and the app works fully
without a network. **Cloud sync** (Settings → *Sync to cloud*, on by default)
keeps the phone and your account on thingsfinder.xyz — or your own server —
in step once you sign in.

## Open and run

1. Android Studio (Narwhal / 2025.1 or newer) → *Open* → this `android/` folder.
2. Let Gradle sync. If Studio offers AGP / dependency upgrades, accepting them is fine.
3. Run the `app` configuration on an emulator or device (minSdk 24).

No server is needed to use the app. Network use:

- **Cloud sync** — only after you sign in (Settings → Cloud sync). Default
  server `https://thingsfinder.xyz`, changeable in the sign-in dialog (https
  only). The server must run the updated PHP code in this repo
  (`includes/sync.php`, `POST /api/sync`).
- The optional barcode-name lookup (Open Food Facts / UPCitemdb), which can be
  turned off in Settings.

## Cloud sync — how it works

- Sign-in sends your password once to `POST /api/auth/login`; the phone keeps
  the returned token (in a DataStore file excluded from Android backups).
  Sign out revokes it on the server.
- Every row has a `uuid` and an `updated_at`. A sync sends what changed on the
  phone since the last push plus pending deletes, and gets back everything
  that changed on the server since the last cursor. **Conflicts: last write
  wins, per row** (same rule on both sides).
- Runs ~15 s after a local change, shortly after app start, and hourly in the
  background (WorkManager, only with a network). *Sync now* in Settings runs
  it immediately.
- Syncs your **own** account only (not accounts shared with you on the web).
- Known limits: deleting a barcode on the phone doesn't delete it on the
  server (it comes back); two devices editing the same row offline → the later
  edit wins; phone clocks that are badly wrong can win conflicts they
  shouldn't (the server rejects timestamps more than a day in the future).

## Database file export / import

Settings → *Database file*: **Export database (.sqlite)** writes the app's
SQLite file (WAL checkpointed first) wherever you choose; **Import database**
checks the file (SQLite header, thingsFinder tables, schema version, integrity)
and after confirmation replaces the phone's database and restarts the app.
The web server's `data/database.sqlite` is recognised and refused — use cloud
sync to bring web data to the phone. The JSON backup is still there as a
readable alternative.

## Layout

```
app/src/main/java/app/thingsfinder/
  domain/        pure Kotlin ports of the PHP rules (slugs, import parser, OCR lines, prompt, box links)
  data/          Room entities/DAOs, InventoryRepository, BarcodeRepository, settings, JSON backup, .sqlite export/import
  sync/          cloud sync: wire models, OkHttp client, session store, SyncEngine, WorkManager scheduling
  lookup/        external barcode-name lookup (OkHttp)
  platform/      ML Kit OCR, Google code scanner, QR (ZXing), label PNG, sharing, file I/O
  ui/            theme (from assets/style.css), navigation, screens + ViewModels, shared components
```

Architecture: single activity · Navigation Compose (type-safe routes) ·
Screen → ViewModel (`StateFlow<UiState>`) → Repository → Room. Manual DI in
`AppContainer`.

Testing: see `TESTING.md`.
