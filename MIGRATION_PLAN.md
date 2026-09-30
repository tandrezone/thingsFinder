# thingsFinder → Android migration plan

## 1. Overview

thingsFinder is a small self-hosted inventory app: **places** (Garage, Attic…)
contain **boxes**, and **items** (name + quantity) live either in a box or
loose in a place. It has search across everything, a shared barcode → name
register (with optional Open Food Facts / UPCitemdb lookups), a QR code and a
printable label per box, "add items from a photo" (Tesseract OCR of a written
list), and JSON import of item lists (with an LLM prompt for turning a photo
into that JSON). The web version adds multi-user accounts with view/edit
sharing.

Stack today: plain PHP + **SQLite** (not MySQL) + HTML/CSS, a session-cookie
JSON API in `api.php`, no framework.

## 2. Feature inventory

Status legend: **done** · **partial** · **deferred** · **n/a** (does not apply to an on-device app).

| Web page / action | Purpose | Auth | Tables | API endpoint | Android screen | Status |
|---|---|---|---|---|---|---|
| `/` list places (+ counts) | Home | login | places, boxes, items | GET /api/places | **Places** | done |
| `/` create / rename / delete place | Manage places | edit | places | POST/PUT/DELETE /api/places | Places (FAB + card menu) | done |
| `/place/{p}` boxes + loose items | Place detail | login | boxes, items | GET /api/places/{id}/boxes, /items | **Place detail** | done |
| create / rename / move / delete box | Manage boxes | edit | boxes | POST /api/places/{id}/boxes, PUT/DELETE /api/boxes/{id} | Place detail, Box detail menu | done |
| rename / delete place (from place page) | | edit | places | PUT/DELETE /api/places/{id} | Place detail menu | done |
| `/place/{p}/{b}` box items | Box detail | login | items | GET /api/boxes/{id}/items | **Box detail** | done |
| create item (name, qty, barcode) | Add item | edit | items, barcode_items | POST …/items | Add-item bottom sheet | done |
| rename item / change qty | | edit | items | PUT /api/items/{id} | Item menu → Edit dialog | done |
| move item (box ↔ place) | | edit | items | PUT /api/items/{id} {box_id/place_id} | Item menu → Move dialog | done |
| delete item | | edit | items | DELETE /api/items/{id} | Item menu → confirm | done |
| barcode scan (camera, `scan.js`) | Fill barcode | edit | — | — | Google code scanner (no camera permission needed) | done |
| external barcode lookup | Suggest name | login | — | GET /api/lookup/{code} | Add-item sheet (runs on the phone, toggle in Settings) | done |
| import items from JSON + LLM prompt | Bulk add | edit | items | — | "Import JSON" → review sheet; "Copy prompt" | done (review step added) |
| `/import-schema.json`, `/import-prompt.txt` | Contract docs | public | — | — | Prompt copy/share in import sheet | partial (schema not shown in-app) |
| add items from a photo (OCR) | Bulk add | edit | items (session review) | — | ML Kit on-device text recognition → review sheet | done |
| box QR code (SVG/PNG download) | Label | login | boxes.share_token | — | Box detail QR card, share PNG | done |
| printable label SVG/PNG (`?w,h,dpi`) | Label | login | boxes | — | "Share label" (PNG 50×30 mm @300 dpi) | partial (fixed size, no SVG) |
| `/view/{token}` public read-only page | Scan box label | public | boxes, items | — | In-app "Scan box QR" + `thingsfinder://box/{token}` deep link | done (on-device only) |
| `/api/boxes/{id}/contents` | JSON view | login | | GET | — | n/a |
| `/search?q=` | Search items | login | items, boxes, places | GET /api/search | **Search** | done |
| `/barcodes` register list / add / rename / delete | Barcode register | edit | barcode_items | /api/barcodes | **Barcodes** | done |
| `/setup`, `/login`, `/logout` | Accounts | public | users | — | — | n/a (single-user device, data never leaves phone) |
| `/people` (sharing), `/switch-context` | Sharing | login | shares | — | — | deferred (needs server sync) |
| `/account` change password | Account | login | users | — | — | n/a |
| — (new) backup to / restore from file | Safety net | — | all | — | **Settings** → JSON backup | done |
| — (new) export / import the SQLite file | Safety net / move phones | — | all | — | **Settings** → Database file | done |
| — (new) cloud sync | Phone ↔ web | token | all + api_tokens, sync_tombstones | POST /api/auth/login, /api/auth/logout, GET /api/me, POST /api/sync | **Settings** → Cloud sync (on by default) | done |
| — (new) settings | Lookup toggle | — | DataStore | — | **Settings** | done |

## 3. Data model

Server (SQLite, `includes/db.php`):

```
users(id, username, password_hash)
shares(id, owner_id, user_id, permission 'view'|'edit')
places(id, owner_id, name, slug)                  UNIQUE(owner_id, slug)
boxes(id, place_id→places CASCADE, name, slug, share_token)   UNIQUE(place_id, slug)
items(id, box_id→boxes CASCADE, place_id→places CASCADE, name, quantity)  CHECK exactly one of box_id/place_id
barcode_items(barcode PK, name)
```

Android (Room, `android/app/src/main/java/.../data/db`): the same four
inventory tables minus `owner_id`/users/shares, plus two sync-ready columns on
every row:

- `uuid` — stable, globally unique id, so a future backup API can match rows
  across devices without depending on local autoincrement ids.
- `updated_at` (epoch millis) — for "last write wins" when sync arrives.

Slugs are kept (same `slugify` rules, same `-2`, `-3` de-duplication) so
data round-trips with the web app unchanged. The items CHECK constraint is
enforced in the repository (Room annotations can't declare CHECK), and the
FKs use `ON DELETE CASCADE` exactly like the server.

## 4. Auth today → on the phone

Today: username/password (`password_hash`), PHP session cookie, per-owner
scoping and view/edit shares; the API reuses the session cookie.

On Android: **no login**. The project rule is *"Everything is stored on the
android phone, API calls for backups will be added in the future"*, so the
app is single-user and fully offline; the device lock is the access control.
When the backup API is added, it should use bearer tokens (an `api_tokens`
table storing SHA-256 hashes, `Authorization: Bearer`) rather than the
session cookie — outlined in §7.

## 5. Strategy

| Option | Verdict |
|---|---|
| **API backend + native Kotlin/Compose client** (skill default) | Not used as-is: it makes the phone a thin client of the PHP server, contradicting the project rule that data lives on the phone. |
| **Offline-first native Kotlin/Compose app with Room as the only store** | **Chosen.** Matches the project rule, works with no server or network, and keeps the door open for a backup API later (uuid/updated_at columns, JSON backup format mirroring the server model). |
| Capacitor hybrid | Would need the PHP logic rewritten in JS anyway (no PHP on device); worse native feel. |
| WebView wrapper | Needs a reachable server — contradicts "stored on the phone"; the camera/barcode issues on plain-http LAN (README) would remain. |

**Deviation from the skill, stated prominently:** Phase 2 (adding a JSON API
to the PHP backend) is **deferred**. The web app is not modified at all.
Because there's no server in the loop, the business rules (slugs, item
placement, barcode "known wins", import parsing, OCR line cleaning, external
lookup) are **ported to Kotlin**. That is a deliberate second copy; each
ported function names its PHP origin in a comment so the two can be kept in
step.

Architecture: single activity, Navigation Compose (type-safe routes),
Screen → ViewModel (`StateFlow<UiState>`) → Repository → Room DAO. Manual
DI via an `AppContainer` (small app; no Hilt). DataStore for settings.
OkHttp only for the optional barcode-name lookups. ML Kit (bundled, on
device) for OCR, Google code scanner for barcodes/QR, ZXing for generating
QR codes.

## 6. Risks and gaps

- **Build not verified here.** The session had no Android SDK and no access to
  Google Maven / Maven Central, so Gradle was never run. Dependency versions
  were chosen as a known-compatible mid-2025 set; let Android Studio suggest
  upgrades after the first sync.
- **Two copies of business logic** (PHP + Kotlin) — see §5.
- **No sharing / multi-user** on the phone until a sync API exists.
- **QR labels printed from the web app** encode `http://<server>/view/<token>`.
  The in-app scanner accepts those URLs and matches the token, but that only
  finds a box if its data was restored from a backup that carried the same
  token (the backup format does). Labels generated on the phone encode
  `thingsfinder://box/<token>`.
- **Code scanner requires Google Play services** (downloaded on first use).
  Typing a barcode always works.
- **External lookup** sends the barcode number (only) to Open Food Facts /
  UPCitemdb, same as the web version; toggle in Settings.

Security issues found in the existing PHP (not modified — listed for follow-up):

1. **IDOR in `index.php` POST handlers.** `rename_item`, `delete_item`
   (`handle_item_action`), and the place page's `rename_box` / `delete_box`
   by `id` run `UPDATE/DELETE … WHERE id = ?` without checking that the row
   belongs to the active owner. Any user with edit rights on *their own*
   account can rename/delete another account's items or boxes by posting a
   guessed id. Fix: scope with the same joins `api_find_item` / `api_find_box`
   use.
2. **No CSRF protection** on any form (acknowledged in README).
3. `api.php` returns `'Server error: ' . $e->getMessage()` to clients — can
   leak SQL/paths. Return a generic message and log the detail.
4. `api.php` has no `display_errors=0` / output buffering — a PHP warning
   would corrupt JSON responses.
5. Session cookie flags (`HttpOnly`, `SameSite`, `Secure`) should be checked
   in `includes/auth.php` if exposed beyond a LAN.

## 7. Cloud sync (built)

Server (`includes/sync.php`, wired in from `includes/db.php`, `includes/auth.php`, `api.php`, `.htaccess`):

- `api_tokens(user_id, token_hash SHA-256, device_name, expires_at +365 d, last_used_at)`.
  `POST /api/auth/login` returns a 64-hex token; every `/api/*` route accepts
  `Authorization: Bearer …` as well as the session cookie. Token requests never
  start a PHP session and only see the token owner's own data.
- `places/boxes/items` gain `uuid`, `updated_at` (conflict clock) and `changed_at`
  (server write time = sync cursor). SQLite triggers fill them for web-UI writes
  and record deletes in `sync_tombstones`, so `index.php` needed no changes.
  Existing databases are migrated in place on the first request.
- `POST /api/sync {since, places, boxes, items, deleted, barcodes}` applies the
  phone's rows (last write wins by `updated_at`, ownership checked per row, a
  uuid owned by another account is refused), then returns everything with
  `changed_at > since` plus tombstones and the barcode register.
- Also fixed while there: `api.php` no longer returns exception text to clients
  and never prints PHP warnings into JSON.
- Test: `tests/sync_smoke.sh` (37 curl checks against `php -S`).

Android: `sync/` package — Room v2 adds `sync_tombstones`; SyncEngine pushes rows
with `updated_at > lastPushAt`, applies pulled rows with the same LWW rule;
WorkManager runs it after local edits, at start and hourly.

Deploy: copy `includes/sync.php` and the changed `api.php`, `includes/auth.php`,
`includes/db.php`, `.htaccess` to the server. The schema upgrades itself.
Apache needs `mod_setenvif` (usually on) for the Authorization header.
