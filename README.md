# thingsFinder

A tiny self-hosted app for keeping track of where your stuff is: which **place**
(garage, attic, kitchen…) and which **box** inside it. Every box gets a
permanent URL like `/place/garage/tools-bin-2`, and the search bar finds any
item by name across every place and box.

Built with plain PHP + SQLite + HTML/CSS (no framework, no build step). Each
person gets their own login and their own places/boxes/items; you can invite
someone else to see (or edit) your stuff too — see [Accounts &
sharing](#accounts--sharing).

## Running it

Requires PHP with the `pdo_sqlite` extension (bundled with PHP by default).

```bash
cd thingsfinder
php -S localhost:8000 router.php
```

Open http://localhost:8000 — the SQLite database (`data/database.sqlite`) is
created automatically on first request, and the first thing you'll see is a
one-time "set up your account" page (see below).

### Deploying to Apache instead

If you'd rather run this on a normal Apache/LAMP host instead of the PHP
built-in server, an `.htaccess` with the equivalent `mod_rewrite` rules is
already included — just drop the folder in your webroot and make sure
`AllowOverride All` is set for it. `router.php` is only used by the `php -S`
built-in server and is ignored by Apache.

## Using the UI

- **Home (`/`)** — list of places, add new ones.
- **`/place/{place}`** — boxes inside that place, *and* any items placed
  directly in it (not every item needs a box — a rake or a mop can just live
  in "Garage" with no box in between); add/rename/delete boxes and place-level
  items.
- **`/place/{place}/{box}`** — items inside that box, add/rename/delete items,
  plus a QR code and a printable label for the box (see below).
- **`/search?q=...`** — search every item by name, with a link straight to its
  place (and box, if it's in one).

Every rename/delete is a plain HTML form (no JavaScript required). Items also
have a **quantity** (defaults to 1) — set it when adding an item, or change it
later from the item's menu; anything more than 1 shows as a small "×N" badge
on the item.

## Accounts & sharing

The very first time you open thingsFinder, `/setup` asks you to pick a
username and password — that becomes the first account, and anything already
in the database (from an older, login-less copy of thingsFinder) becomes
that account's own stuff. After that, `/setup` stops working and everyone
signs in at `/login` instead.

Every place belongs to exactly one account. There's no self-signup — from
**People** (in the account menu, top right), you add someone by choosing a
username and a starting password for them yourself; if that username
already exists, leave the password blank and it just grants that existing
account access to yours instead. Each person you add gets one of two
permission levels over *everything* you own (there's no per-place sharing):

- **View only** — they can look at all your places, boxes, and items, and
  use search, but every add/rename/delete control is hidden, and the server
  rejects the underlying request even if someone tries it directly.
- **Can edit** — full access, as if it were their own: create, rename, and
  delete places, boxes, and items.

You can change or revoke either at any time from **People**; revoking takes
effect immediately.

Once someone has shared their stuff with you, a "My stuff" switcher appears
in the top bar next to the logo — pick your own account or any account
that's shared with you, and everything you see (including search) scopes to
whichever one is currently selected. Your own account always has full
access to your own stuff regardless of any share.

Change your own password any time from **Account** in the same menu.

**What sharing doesn't do:** there's no email, no invite links, and no
password reset flow — if someone forgets their password, sign in as the
account owner (or that user, if they still remember it) and set a new one
from **Account**. Passwords are hashed with PHP's `password_hash()`; there's
otherwise no CSRF protection, matching the rest of the app's plain-HTML-forms
approach — fine on a home network or behind a VPN, worth keeping in mind if
you expose thingsFinder to the open internet.

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

Every box page shows a QR code. Scanning it opens a public, read-only page —
`/view/{token}` — showing that box's name, its place, and every item in it,
with no login and no add/rename/delete controls of any kind: whoever scans
it can only look. That's on purpose, so you (or anyone else who can see the
physical label) can check a box's contents without needing an account or
even a network connection to your account's data beyond that one box.

The `{token}` is a random, unguessable 128-bit value (`boxes.share_token`)
rather than the box's numeric ID — nothing else about your account (your
username, your other places/boxes, or even this box's own numeric ID) is
reachable from it. Anyone with the link or a photo of the QR code can view
that one box, indefinitely — there's currently no way to invalidate or
regenerate a token short of deleting and re-creating the box, so treat the
printed label with the same care you'd give a link you don't want to hand
out freely (though in practice, most people only see it printed on a box
sitting in their own garage).

If you're signed in and want the same data as authenticated JSON instead,
`/api/boxes/{id}/contents` still exists — see [JSON API](#json-api).

- Print a label: use the "Download QR (SVG)" or "Download QR (PNG)" links on
  the box page.
- QR generation is done with a small vendored, dependency-free PHP library
  (`includes/qrcode-lib.php`, MIT-licensed, by Kazuhiko Arase) — no Composer
  and no GD extension required for the SVG version. PNG downloads use GD if
  it's available on your server (it usually is).

## Printable labels

Every box page also has a "Printable label" block: a small tag-style image
sized for a label printer (Dymo, Brother, NIIMBOT, or similar), with the
box's QR code, its name, its place's name, filled box/location-pin icons on
light accent "chips", a double-line frame in the app's own accent color, a
dashed divider between the QR and the text, and a decorative corner hole —
so it reads like it belongs with the rest of the app, not just a bare QR
code with text next to it.

- `/place/{place}/{box}/label.svg` — vector; scales perfectly to any size and
  is what most label-printer apps and browsers can print directly.
- `/place/{place}/{box}/label.png` — rasterized (needs GD, same as the QR
  PNG). Text is drawn with the vendored Liberation Sans font
  (`assets/fonts/`, SIL Open Font License — see `assets/fonts/LICENSE-OFL.txt`)
  when GD has FreeType support, so it stays crisp regardless of what fonts
  happen to be installed on your server; it falls back to GD's plain bitmap
  font if FreeType isn't available, so PNG export never hard-fails.
- Both accept query params to change the size: `?w=` and `?h=` in
  millimeters (default 50×30), and `?dpi=` for the PNG's resolution (default
  300). For example, `/place/garage/tools-bin/label.png?w=70&h=40&dpi=203`.
- Long box or place names shrink to fit first, and only truncate (with an
  ellipsis) if they still don't fit — which mostly happens on the smallest
  label sizes. If you have long names, use a bigger `?w=`/`?h=`, or keep box
  names short and let the QR code carry the rest of the detail.

## Add items from a photo

Both box pages and place pages have an "Add items from a photo" tile (below
the QR/label blocks, above the item list). Take or upload a photo of a
**printed or handwritten list** — a packing list, a receipt, a sticky note —
and thingsFinder reads the text and turns each line into a candidate item,
which you can edit or remove before anything is actually added. Tap **Add N
items** and whatever's left in the list gets created in that box or place at
once.

This is text OCR, not object recognition — point the camera at *writing*,
not at the items themselves; a photo of a pile of tools won't produce a
list of tool names.

- Requires the free, open-source [Tesseract OCR](https://github.com/tesseract-ocr/tesseract)
  command-line program installed on the server, plus PHP's `shell_exec()`
  enabled (some hosts disable it by default). If either is missing, the tile
  says so instead of showing the upload form — everything else in
  thingsFinder works fine either way, this is the one feature with an extra
  system dependency (the same idea as GD being optional for PNG export).
  - **Debian/Ubuntu:** `sudo apt install tesseract-ocr`
  - **macOS (Homebrew):** `brew install tesseract`
  - **Windows:** install from the [tesseract-ocr/tesseract releases](https://github.com/tesseract-ocr/tesseract/releases)
    or via `choco install tesseract`, and make sure `tesseract.exe` is on
    your `PATH`.
- On a phone, the same file input opens the camera directly (no JavaScript
  needed); on desktop it's a normal file picker.
- Photos must be JPEG, PNG, WEBP, or BMP, 8MB or smaller. **HEIC photos from
  iPhones aren't supported** — set the iPhone's camera format to "Most
  Compatible" (Settings → Camera → Formats) so it saves JPEGs, or convert
  the photo first.
- **Privacy:** the uploaded photo is only ever written to a temporary file
  for the moment it takes Tesseract to read it, then deleted immediately —
  thingsFinder never keeps the photo itself, only the text lines it found.
- The in-progress review list (what you haven't added or discarded yet)
  lives in your session, not the database, so it's private to you and
  disappears if you log out or clear cookies before adding it.

## JSON API

Everything under `/api` returns JSON, and every route requires being logged
in (the same session cookie as the regular site — `fetch()` calls from
thingsFinder's own pages send it automatically; from a script, send the
`PHPSESSID` cookie you got from `POST /login`). Every places/boxes/items
route is scoped to whichever account is currently active for that session
(see [Accounts & sharing](#accounts--sharing)) — a view-only share can call
any `GET` here but a `POST`/`PUT`/`PATCH`/`DELETE` gets a `403`. The barcode
register (`/api/barcodes...`) is the exception: it's shared across every
account on the install, same as the `/barcodes` page, so it isn't scoped to
the active account, just to being logged in.

| Method | Path | Body | Description |
|---|---|---|---|
| GET | `/api/places` | | List all places (in the active account) |
| POST | `/api/places` | `{"name": "Garage"}` | Create a place |
| GET | `/api/places/{id}` | | Get one place |
| PUT | `/api/places/{id}` | `{"name": "..."}` | Rename a place |
| DELETE | `/api/places/{id}` | | Delete a place (and its boxes/items) |
| GET | `/api/places/{id}/boxes` | | List boxes in a place |
| POST | `/api/places/{id}/boxes` | `{"name": "Bin 2"}` | Create a box |
| GET | `/api/places/{id}/items` | | List items placed directly in a place (not in a box) |
| POST | `/api/places/{id}/items` | `{"name": "Rake", "quantity": 1}` | Create an item directly in a place |
| GET | `/api/boxes/{id}` | | Get one box |
| PUT | `/api/boxes/{id}` | `{"name": "..."}` | Rename a box |
| DELETE | `/api/boxes/{id}` | | Delete a box (and its items) |
| GET | `/api/boxes/{id}/items` | | List items in a box |
| POST | `/api/boxes/{id}/items` | `{"name": "Glue gun", "quantity": 1}` | Create an item in a box (`quantity` optional, defaults to 1) |
| GET | `/api/boxes/{id}/contents` | | Box + place + all its items — an authenticated JSON equivalent of the box's public `/view/{token}` page |
| GET | `/api/items/{id}` | | Get one item |
| PUT | `/api/items/{id}` | `{"name": "...", "quantity": 2}` | Rename an item and/or update its quantity |
| DELETE | `/api/items/{id}` | | Delete an item |
| GET | `/api/search?q=glue` | | Search items by name; each result includes its place + box + URL |
| GET | `/api/barcodes` | | List every registered barcode -> item association |
| POST | `/api/barcodes` | `{"barcode": "...", "name": "..."}` | Register (or relabel) a barcode |
| GET | `/api/barcodes/{code}` | | Look up one barcode; 404 if not registered |
| PUT | `/api/barcodes/{code}` | `{"name": "..."}` | Rename the item a barcode resolves to |
| DELETE | `/api/barcodes/{code}` | | Remove a barcode association |
| GET | `/api/lookup/{code}` | | Best-effort external name suggestion for a barcode; `{"barcode","name","source"}`, name/source null if nothing found. Never touches the register itself. |

Example (`-c`/`-b` keep the session cookie from login for the follow-up calls):

```bash
curl -c cookies.txt -X POST localhost:8000/login -d "username=you" -d "password=yourpassword"
curl -b cookies.txt -X POST localhost:8000/api/places -d '{"name":"Garage"}'
curl -b cookies.txt "localhost:8000/api/search?q=glue"
```

## Data model

```
users         (id, username, password_hash)
shares        (id, owner_id, user_id, permission)     -- permission is 'view' or 'edit'; one row per (owner, user)
places        (id, owner_id, name, slug)               -- slug unique within its owner
boxes         (id, place_id, name, slug, share_token)  -- slug unique within its place; share_token is the public /view/ link
items         (id, box_id, place_id, name, quantity)   -- exactly one of box_id/place_id is set
barcode_items (barcode, name)                          -- barcode is the primary key; shared across all accounts
```

An item lives either in a box or directly in a place — never both, never
neither (enforced by a `CHECK` constraint). A `shares` row grants `user_id`
either `view` or `edit` access to everything `owner_id` owns — there's no
per-place sharing, and an owner always implicitly has `edit` on their own
data without a `shares` row. If you upgrade from an older copy of
thingsFinder, the database is migrated in place the first time it runs:

- Pre-quantity/pre-place-items copies: existing items keep their box, gain a
  `quantity` of 1, and nothing is lost.
- Pre-login copies (no `users`/`shares`/`owner_id`/`share_token` at all):
  every existing place is adopted by whichever account you create at
  `/setup`, and every existing box gets a freshly-generated `share_token` so
  its QR code and printable label keep working, pointing at the new
  `/view/{token}` page instead of the old JSON endpoint.

Deleting a place deletes its boxes and everything in them, plus any items
placed directly in it; deleting a box deletes its items; deleting a user
deletes everything they own, plus every `shares` row involving them (all via
SQLite `ON DELETE CASCADE`). Slugs are auto-generated from the name and
de-duplicated (`bin-2`, `bin-2-2`, …) so URLs are always stable and unique
within their owner.

## Project structure

```
thingsfinder/
  router.php          # dev-server router (php -S)
  index.php            # UI front controller (all HTML pages)
  api.php              # JSON API front controller
  includes/db.php        # SQLite connection + schema + slug helpers
  includes/auth.php      # login/session/sharing/permission helpers
  includes/ocr.php        # "add items from a photo": Tesseract OCR wrapper
  includes/helpers.php   # JSON/HTML/routing helpers
  includes/qrcode.php    # thin wrapper: URL -> inline SVG / downloadable SVG/PNG
  includes/qrcode-lib.php # vendored MIT QR encoder (no deps, no GD required)
  includes/label.php     # printable label generator (QR + names + border + icons, SVG/PNG)
  assets/style.css     # UI styling
  assets/scan.js       # optional camera barcode scanning (progressive enhancement)
  assets/fonts/        # vendored Liberation Sans TTF, used by the PNG label (SIL OFL license)
  assets/favicon.svg   # the box mark — source of truth for every icon below
  assets/apple-touch-icon.png # 180px iOS home-screen icon (cream box on terracotta)
  assets/icon-192.png    # manifest icon
  assets/icon-512.png    # manifest icon
  favicon.ico          # pixel-tuned 16/32/48 bitmaps, at the webroot on purpose
  site.webmanifest     # app name, colors and icons for "add to home screen"
  data/database.sqlite # created automatically, gitignored
  .htaccess            # Apache routing (alternative to router.php)
```

### Icons

The favicon is a flat box mark drawn in the UI's own terracotta
(`--accent` / `--accent-dark` from `assets/style.css`), matching the 📦 in
the topbar. `assets/favicon.svg` is the master; modern browsers use it
directly and scale it to whatever size they need. `favicon.ico` exists
because browsers request `/favicon.ico` on their own whether you link it or
not, and its bitmaps are drawn on whole-pixel edges so the mark stays crisp
at 16px instead of blurring the way a downscaled SVG does. The
`apple-touch-icon` inverts to a cream box on a solid terracotta square,
since iOS puts home-screen icons on arbitrary wallpapers and masks the
corners itself.

All the raster files are regenerated from the SVG — any SVG rasterizer will
do, e.g. `rsvg-convert -w 192 -h 192 assets/favicon.svg -o assets/icon-192.png`.
Both routers (`router.php` and `.htaccess`) already serve real files
straight through, so none of these need a route.

The `<head>` links live in one place — `favicon_tags()` in
`includes/helpers.php` — so the two page shells in `index.php` (the normal
app layout and the public `/view/{token}` layout) can't drift apart.

## Notes / possible extensions

- Sharing is all-or-nothing per account (view or edit everything an owner
  has) rather than per-place — simpler to reason about, but if you wanted
  to share just one place with someone (a shared garage, say, without
  handing over the rest of your house), that'd need a schema change.
- There's no CSRF protection anywhere in the app (consistent with its
  plain-HTML-forms, no-JavaScript-required design) and no password reset
  flow — fine on a home network or behind a VPN, worth adding both if you
  expose thingsFinder to the open internet.
- Items have a name and a quantity. Notes or a photo per item would be easy
  further additions to the `items` table and the corresponding forms.
