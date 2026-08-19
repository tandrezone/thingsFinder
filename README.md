# thingsFinder

A tiny self-hosted app for keeping track of where your stuff is: which **place**
(garage, attic, kitchen…) and which **box** inside it. Every box gets a
permanent URL like `/place/garage/tools-bin-2`, and the search bar finds any
item by name across every place and box.

Built with plain PHP + SQLite + HTML/CSS (no framework, no build step, no
login).

## Running it

Requires PHP with the `pdo_sqlite` extension (bundled with PHP by default).

```bash
cd thingsfinder
php -S localhost:8000 router.php
```

Open http://localhost:8000 — the SQLite database (`data/database.sqlite`) is
created automatically on first request.

### Deploying to Apache instead

If you'd rather run this on a normal Apache/LAMP host instead of the PHP
built-in server, an `.htaccess` with the equivalent `mod_rewrite` rules is
already included — just drop the folder in your webroot and make sure
`AllowOverride All` is set for it. `router.php` is only used by the `php -S`
built-in server and is ignored by Apache.

## Using the UI

- **Home (`/`)** — list of places, add new ones.
- **`/place/{place}`** — boxes inside that place, add/rename/delete boxes.
- **`/place/{place}/{box}`** — items inside that box, add/rename/delete items,
  plus a QR code for the box (see below).
- **`/search?q=...`** — search every item by name, with a link straight to its
  place + box.

Every rename/delete is a plain HTML form (no JavaScript required).

## Barcodes

The "Add an item" form on a box page has a barcode field. Scan (or type) a
barcode there:

- If that barcode is already registered, thingsFinder adds the item it's
  linked to — you don't need to type a name at all.
- If it's not registered yet, thingsFinder tries a couple of free, keyless
  product-lookup APIs (Open Food Facts, then UPCitemdb's trial endpoint) to
  suggest a name. The suggestion (if any) is filled into the name field for
  you to confirm or edit — nothing is saved until you tap **Add**. Once you
  do, thingsFinder adds the item *and* remembers the barcode, so scanning it
  again anywhere will resolve straight to that name.
- If no suggestion is found either, you just type the name yourself — same
  as before.

**Privacy note:** looking up an unregistered barcode sends that barcode
number to Open Food Facts and/or UPCitemdb over the network — nothing else
about your inventory. If you'd rather thingsFinder never make outbound
requests, set `EXTERNAL_BARCODE_LOOKUP_ENABLED` to `false` in
`includes/helpers.php`; barcode scanning still works, it'll just always ask
you to type the name for anything not already in your own register.

On a phone with a modern browser (Chrome/Edge on Android; camera-based
scanning isn't yet supported by Safari on iOS), a "Scan" button appears next
to the barcode field and opens the camera — point it at a barcode and the
rest happens automatically. This uses the browser's native `BarcodeDetector`
API, feature-detected client-side (`assets/scan.js`); there's no vendored
scanning library and no build step. Everywhere else (or with a Bluetooth/USB
barcode-scanner "gun", which just types digits + Enter into the field), the
barcode field is a plain text input, so the whole flow still works with
JavaScript off.

Manage the register directly at `/barcodes` — rename or remove an
association if something got scanned wrong.

### No "Scan" button showing up?

Browsers only allow camera access on a *secure context* — an `https://`
page, or `http://localhost`/`127.0.0.1`. If you're reaching thingsFinder
over plain `http://` at a LAN address (e.g. `http://192.168.1.50:8000`,
which is the normal way to run this on a home network), Android Chrome
hides the camera API entirely and the Scan button won't appear. The add-item
form will now tell you this directly (a small note appears where the Scan
button would be), but the short version is one of:

- **Quickest fix, no extra setup:** on the Android phone, go to
  `chrome://flags/#unsafely-treat-insecure-origin-as-secure`, add your
  thingsFinder URL (e.g. `http://192.168.1.50:8000`) to the list, enable the
  flag, and relaunch Chrome. This tells Chrome to trust camera access on
  that specific address only.
- **More robust:** put thingsFinder behind something that terminates HTTPS
  — a reverse proxy like Caddy (automatic certs if you have a domain), a
  Tailscale node (`tailscale serve https`), or a tunnel like Cloudflare
  Tunnel/ngrok.
- Typing the barcode always works regardless (from the keyboard, or a
  Bluetooth/USB scanner "gun") — camera scanning is a convenience on top of
  that, not a requirement.

If you *are* already on HTTPS or localhost and still don't see the button,
it's more likely the browser itself (Safari on iOS doesn't support this
API yet) or that the barcode-detection component isn't available on that
device — the note will say which.

## QR codes on boxes

Every box page shows a QR code. Scanning it opens
`/api/boxes/{id}/contents` — the JSON contents of that box (its name, its
place, and every item in it) — so you can point a phone straight at a box's
label and see what's inside without touching the UI.

- Print a label: use the "Download QR (SVG)" or "Download QR (PNG)" links on
  the box page.
- QR generation is done with a small vendored, dependency-free PHP library
  (`includes/qrcode-lib.php`, MIT-licensed, by Kazuhiko Arase) — no Composer
  and no GD extension required for the SVG version. PNG downloads use GD if
  it's available on your server (it usually is).

## JSON API

Everything under `/api` returns JSON.

| Method | Path | Body | Description |
|---|---|---|---|
| GET | `/api/places` | | List all places |
| POST | `/api/places` | `{"name": "Garage"}` | Create a place |
| GET | `/api/places/{id}` | | Get one place |
| PUT | `/api/places/{id}` | `{"name": "..."}` | Rename a place |
| DELETE | `/api/places/{id}` | | Delete a place (and its boxes/items) |
| GET | `/api/places/{id}/boxes` | | List boxes in a place |
| POST | `/api/places/{id}/boxes` | `{"name": "Bin 2"}` | Create a box |
| GET | `/api/boxes/{id}` | | Get one box |
| PUT | `/api/boxes/{id}` | `{"name": "..."}` | Rename a box |
| DELETE | `/api/boxes/{id}` | | Delete a box (and its items) |
| GET | `/api/boxes/{id}/items` | | List items in a box |
| POST | `/api/boxes/{id}/items` | `{"name": "Glue gun"}` | Create an item |
| GET | `/api/boxes/{id}/contents` | | Box + place + all its items — what the box's QR code links to |
| GET | `/api/items/{id}` | | Get one item |
| PUT | `/api/items/{id}` | `{"name": "..."}` | Rename an item |
| DELETE | `/api/items/{id}` | | Delete an item |
| GET | `/api/search?q=glue` | | Search items by name; each result includes its place + box + URL |
| GET | `/api/barcodes` | | List every registered barcode -> item association |
| POST | `/api/barcodes` | `{"barcode": "...", "name": "..."}` | Register (or relabel) a barcode |
| GET | `/api/barcodes/{code}` | | Look up one barcode; 404 if not registered |
| PUT | `/api/barcodes/{code}` | `{"name": "..."}` | Rename the item a barcode resolves to |
| DELETE | `/api/barcodes/{code}` | | Remove a barcode association |
| GET | `/api/lookup/{code}` | | Best-effort external name suggestion for a barcode; `{"barcode","name","source"}`, name/source null if nothing found. Never touches the register itself. |

Example:

```bash
curl -X POST localhost:8000/api/places -d '{"name":"Garage"}'
curl "localhost:8000/api/search?q=glue"
```

## Data model

```
places        (id, name, slug)
boxes         (id, place_id, name, slug)   -- slug unique within its place
items         (id, box_id, name)
barcode_items (barcode, name)              -- barcode is the primary key
```

Deleting a place deletes its boxes and their items (SQLite `ON DELETE
CASCADE`). Slugs are auto-generated from the name and de-duplicated (`bin-2`,
`bin-2-2`, …) so URLs are always stable and unique.

## Project structure

```
thingsfinder/
  router.php          # dev-server router (php -S)
  index.php            # UI front controller (all HTML pages)
  api.php              # JSON API front controller
  includes/db.php        # SQLite connection + schema + slug helpers
  includes/helpers.php   # JSON/HTML/routing helpers
  includes/qrcode.php    # thin wrapper: URL -> inline SVG / downloadable SVG/PNG
  includes/qrcode-lib.php # vendored MIT QR encoder (no deps, no GD required)
  assets/style.css     # UI styling
  assets/scan.js       # optional camera barcode scanning (progressive enhancement)
  data/database.sqlite # created automatically, gitignored
  .htaccess            # Apache routing (alternative to router.php)
```

## Notes / possible extensions

- No authentication — anyone who can reach the app can view/edit everything.
  Fine for a home network; put it behind a VPN or add basic auth if exposing
  it publicly.
- Items currently only have a name. Quantity, notes, or a photo per item
  would be easy additions to the `items` table and the corresponding forms.
