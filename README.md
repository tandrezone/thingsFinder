# thingsFinder

Self-hosted inventory for places, boxes and items.

**Places** (Garage, Attic…) contain **boxes**, and **items** (name + quantity)
live either in a box or loose in a place. thingsFinder comes as a PHP + SQLite
web app with multi-user sharing, and an offline-first Android app that stores
everything on the phone.

| | Web | Android |
|---|---|---|
| Runs on | Your own server | Your phone |
| Stack | PHP, SQLite, HTML/CSS (no framework) | Kotlin, Jetpack Compose, Room |
| Where data lives | The server's SQLite database | Only on the phone |
| Accounts | Multi-user, with view/edit sharing | None (the device lock is the access control) |
| Network | Needed to reach the server | Works fully offline |

## Features

Both versions:

- Create, rename, move and delete places, boxes and items
- Search across everything
- Barcode scanning and a shared barcode → name register
- Optional name lookup via Open Food Facts / UPCitemdb (only the barcode number is sent)
- Add items from a photo of a written list, with a review step before saving
- JSON import of item lists, with a ready-made LLM prompt for turning a photo into that JSON
- A QR code and printable label for every box

Web only:

- Multiple user accounts with view/edit sharing
- Public read-only box page (`/view/<token>`) for anyone who scans a label
- Labels as SVG or PNG in any size and DPI
- OCR with Tesseract

Android only:

- No account or server needed; nothing leaves the phone
- On-device OCR with ML Kit
- Google code scanner for barcodes and box QR codes (no camera permission needed)
- Backup and restore to a JSON file, or export the whole SQLite database to move to a new phone
- Box labels as PNG (50 × 30 mm at 300 dpi), linking to `thingsfinder://box/<token>`

## Project layout

```
/            PHP web app (index.php, api.php, includes/, .htaccess)
android/     Android app (open this folder in Android Studio)
```

## Getting started

**Web:** copy the files to a PHP server with the SQLite extension enabled,
make sure the web server can write to the database directory, then open
`/setup` in a browser to create the first account.

**Android:** open the `android/` folder in Android Studio, let Gradle sync,
and run the app on a device or emulator. Barcode scanning uses Google Play
services, which download the scanner module on first use; typing a barcode
always works.

## Compatibility between web and Android

Both versions use the same slugs, box tokens and item structure, so data
round-trips between them. QR labels printed from the web app encode
`http://<server>/view/<token>`; the Android scanner accepts these and finds
the box as long as its data was restored from a backup that carries the same
token.

## Roadmap

- Backup API so the Android app can back up to and restore from the web server
- Sharing on Android once that API exists

## Privacy

The Android app stores all data locally. The only network requests are the
optional barcode lookups, which send the barcode number and nothing else.
They can be turned off in Settings.
