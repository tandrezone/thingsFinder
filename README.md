# thingsFinder

Self-hosted inventory for places, boxes and items.

**Places** (Garage, Attic…) contain **boxes**, and **items** (name + quantity)
live either in a box or loose in a place. Everything belongs to a **group**,
and everyone in the group shares it. thingsFinder comes as a PHP + SQLite web
app, and an offline-first Android app that keeps its data on the phone and
can sync with the web app.

| | Web | Android |
|---|---|---|
| Runs on | Your own server | Your phone |
| Stack | PHP, SQLite, HTML/CSS (no framework) | Kotlin, Jetpack Compose, Room |
| Where data lives | The server's SQLite database | Only on the phone |
| Accounts | Open registration, groups with invites | Optional: sign in or register to sync with a server |
| Network | Needed to reach the server | Works fully offline; syncs when online |

## Features

Both versions:

- Create, rename, move and delete places, boxes and items
- Search across everything
- Barcode scanning and a shared barcode → name register
- Optional name lookup via Open Food Facts / UPCitemdb (only the barcode number is sent)
- Add items from a photo of a written list, with a review step before saving
- JSON import of item lists, with a ready-made LLM prompt for turning a photo into that JSON
- A QR code and printable label for every box
- "Add item" and "Remove item" QR codes for every box and place: scanning one
  opens it ready to add something, or to take something out (−1 or remove)
- Registration, and groups: create a group, invite people with a link, its QR
  code, or the group's name + key; everyone in a group can add, edit and
  remove its places, boxes and items

Web only:

- Group owners can rename a group, replace its invite, make members view-only,
  remove members, or delete the group
- Public read-only box page (`/view/<token>`) for anyone who scans a label
- Labels as SVG or PNG in any size and DPI
- OCR with Tesseract

Android only:

- No account or server needed; nothing leaves the phone
- On-device OCR with ML Kit
- Google code scanner for barcodes and box QR codes (no camera permission needed)
- Backup and restore to a JSON file, or export the whole SQLite database to move to a new phone
- Box labels as PNG (50 × 30 mm at 300 dpi), linking to `thingsfinder://box/<token>`
- Syncs one group at a time; switch groups in Settings → Groups

## Project layout

```
web/         Web root — the only public folder: index.php (UI), api.php
             (forwards /api/* to api/), .htaccess, assets/, favicon, manifest
includes/    Shared core used by the web app and the API (DB, auth, groups, sync)
api/         Standalone JSON API: index.php front controller, routes/, src/
router.php   Router for PHP's built-in server (`composer serve`)
android/     Android app (open this folder in Android Studio)
docs/        Feature list and the OpenAPI (Swagger) spec of the JSON API
composer.json, .env.example   PHP dependencies and settings
```

## Documentation

- [docs/FEATURES.md](docs/FEATURES.md) — every feature, web and Android
- [docs/openapi.yaml](docs/openapi.yaml) — OpenAPI 3 spec of every `/api`
  endpoint; browse it at `/docs/swagger.html` under `composer serve`, or paste
  it into <https://editor.swagger.io>

## Getting started

**Web:** needs PHP 8.1+ with the SQLite and mbstring extensions, and
[Composer](https://getcomposer.org).

```sh
composer install            # vendor/ (vlucas/phpdotenv, for .env)
cp .env.example .env        # optional — every setting has a default
composer serve              # http://localhost:8000 (php -S … -t web router.php)
```

On Apache, `sudo ./setup.sh` does all of that (packages, `composer install`,
`.env`, vhost, HTTPS) — or copy the project to any PHP host, point the
document root at `web/`, run `composer install`, and make sure the web server
can write to the database directory. Re-running `setup.sh` on a server set up
before `web/` existed moves the vhost's `DocumentRoot` to `web/` (also in
certbot's `-le-ssl.conf`; backups are kept). Then open `/setup` in a browser to create the first account.
After that anyone can create an account at `/register`.

**Settings** go in `.env` (see [.env.example](.env.example)) or in real
environment variables, which win over `.env`:

| Variable | Default | |
|---|---|---|
| `TF_APP_URL` | the request's host | Public URL of the web app, used in invite links and QR codes |
| `TF_REGISTRATION` | `on` | Open sign-up on `/register` and `POST /api/auth/register` |
| `TF_DB_PATH` | `data/database.sqlite` | SQLite file, absolute or relative to the project root |
| `TF_BARCODE_LOOKUP` | `on` | Ask Open Food Facts / UPCitemdb for unknown barcodes |
| `TF_TOKEN_TTL_DAYS` | `365` | How long an app sign-in stays valid |

Without `composer install` the app still runs, but `.env` is ignored (a
warning is logged); real environment variables still apply. Only `web/` is
served, so `.env`, `vendor/`, `data/`, `includes/` and `composer.*` can't be
requested at all.

**Standalone API:** `api/` only needs `includes/`, `vendor/` and `.env` from
the project root, so it can run on its own host: point a virtual host (say
`api.example.com`) at `api/` — its own `.htaccess` routes everything to
`api/index.php` — or run `composer serve:api` (port 8001). Paths then have
no `/api` prefix (`https://api.example.com/places`). Set `TF_APP_URL` to the
web app's URL so invite links and QR codes point there. The web app keeps
serving the same API under `/api` at the same time (through `web/api.php`).

Upgrading from a version without groups needs no manual step: on first load
each account gets a personal group holding the places it owned, and every
earlier share becomes a membership of the sharer's group at the same
view/edit permission.

**Android:** open the `android/` folder in Android Studio, let Gradle sync,
and run the app on a device or emulator. Barcode scanning uses Google Play
services, which download the scanner module on first use; typing a barcode
always works.

## Compatibility between web and Android

Both versions use the same slugs, box/place tokens and item structure, so
data round-trips between them. QR codes printed from the web app encode
`https://<server>/view/<token>`, `/add/<token>` and `/remove/<token>`; codes
made on the phone encode `thingsfinder://box/<token>[/add|/remove]` and
`thingsfinder://place/<token>/add|remove`. The Android scanner understands
both kinds, and finds the box or place as long as the phone has synced (or
restored) data carrying the same token. Invite links are
`https://<server>/join/<token>`.

## Tests

- `tests/sync_smoke.sh` — the phone sync API (bash, curl, jq); works against
  the standalone API too if you drop the `/api` prefix from its paths
- `tests/groups_e2e.py` — registration, groups, invites and the add/remove
  codes, through the API and the web UI (Python standard library)
- `android/`: `./gradlew testDebugUnitTest`

## Privacy

The Android app stores all data locally. Unless you sign in to a server to
sync, the only network requests are the optional barcode lookups, which send
the barcode number and nothing else. They can be turned off in Settings.
