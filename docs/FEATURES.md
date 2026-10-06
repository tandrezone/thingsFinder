# thingsFinder — functionality

Everything thingsFinder does, by area. Where the web app and the Android app
differ, it says which one. The JSON API is documented separately in
[openapi.yaml](openapi.yaml) (open it in [swagger.html](swagger.html), or
paste it into <https://editor.swagger.io>).

## Data model

- **Groups** own everything. Every place belongs to exactly one group, and all
  members of that group share its places, boxes and items.
- **Places** (Garage, Attic…) belong to a group. They have a name, a URL slug
  that is unique within the group, and a random share token for their QR codes.
- **Boxes** live in a place. They have a name, a slug that is unique within
  the place, and a random share token.
- **Items** have a name and a quantity (1 by default). Each item is in exactly
  one box, or loose in a place.
- **Barcode register**: maps a barcode to an item name. It is shared by
  everyone on the server.

## Accounts

| Feature | Web | Android |
|---|---|---|
| First-run setup: create the first account (`/setup`, only while no account exists) | ✓ | — |
| Open registration: username (3–40 chars: letters, digits, `.` `_` `-`, case-insensitively unique) and password (8+ chars) | ✓ `/register` | ✓ Settings → Cloud sync → Create account |
| Turn registration off with the server env var `TF_REGISTRATION=off` | ✓ | gets a clear error |
| Log in / log out | ✓ session cookie | ✓ bearer token, valid 365 days |
| Change password | ✓ `/account` | — |
| Each new account gets a personal group named "*username*'s things" | ✓ | ✓ |

The phone can be used without an account; in that case its data never leaves
the device.

## Groups and sharing

- Create as many groups as you like. The creator is the group's **owner**.
- Switch the active group from the topbar (web) or Settings → Groups (Android).
  Everything you see and change happens in the active group.
- **Invite people** in three ways:
  - **Link**: `https://<server>/join/<token>`. If the person has no account
    yet, they register first and then land on the join page.
  - **QR code** of that link: shown on the group page, downloadable as SVG or
    PNG, and shareable from the app.
  - **Name + key**: the group's name plus an 8-character key such as
    `K7F3-9QX2`. Case, spaces and the dash don't matter. Enter them under
    Groups → "Join a group".
- **Permissions**: people who join can **edit**, meaning add, rename, move and
  delete anything in the group. A **view-only** member can only look; this
  happens when the owner sets it, or after migrating an old view-only share.
- **Owner-only actions**: rename the group, make a new link and key (old
  invites stop working), change a member between edit and view-only, remove a
  member, and delete the group together with everything in it (you must type
  the group's name to confirm).
- **Members** can leave a group. Owners can't leave; they delete the group
  instead.
- If you end up with no group at all, a new personal group is created for you
  automatically.
- **Upgrading** from the old per-owner sharing happens automatically. Each
  account gets a personal group holding its places, and each old share
  becomes a membership with the same view/edit permission.

## Places, boxes and items

- Create, rename and delete places and boxes. Deleting one deletes everything
  inside it.
- Move a box, with its contents, to another place.
- Add items to a box or loose in a place, with a quantity. Edit an item's name
  and quantity, move it to any box or place in the group, or delete it.
- Search item names across the whole active group. Results show the place and
  the box each item is in.
- Web only: permalinks `/place/<place-slug>` and `/place/<place-slug>/<box-slug>`.

## QR codes and labels

Every code, whether made on the web or on the phone, encodes a web page on
the server, so any phone camera can open it. On a phone, that page shows an
**Open this in the app** note linking to the matching `thingsfinder://` link.

| Code | Encodes | Opens in the app as | What scanning it does |
|---|---|---|---|
| Box **view** code | `https://<server>/view/<token>` | `thingsfinder://box/<token>` | Web: public read-only list of the box's contents, no login needed. App: opens the box. |
| Box / place **add item** code | `https://<server>/add/<token>` | `thingsfinder://box|place/<token>/add` | Opens the box or place with the add-item form already open and focused |
| Box / place **remove item** code | `https://<server>/remove/<token>` | `thingsfinder://box|place/<token>/remove` | Opens the box or place in remove mode: each item gets **−1** (take one out; the last one removes the item) and **Remove** |
| Group **invite** code | `https://<server>/join/<token>` | `thingsfinder://join/<token>` | Offers to join the group |

- The add and remove codes need a login with access to the group, and the
  group becomes the active one. People outside the group get "not found".
- Web downloads: the QR code alone (SVG or PNG) and a printable label (SVG or
  PNG). Labels are 50×30 mm by default; change that with `?w=` and `?h=` in
  mm, and set PNG resolution with `?dpi=`.
- Android: share any code as a PNG, or share a printable label (50×30 mm at
  300 dpi).
- Android codes use the server set in Settings → Cloud (the default server
  when none is set).
- The Android in-app scanner understands both the web URLs and the
  `thingsfinder://` links, and the app opens the default server's links
  directly when Android lets it.

## Adding items faster

- **Barcode scanning**: scan or type a barcode while adding an item. A barcode
  already in the register fills in the name. If it isn't, the app can look it
  up in Open Food Facts or UPCitemdb (only the barcode number is sent), and
  then remembers the name you confirm. On Android the lookup can be turned off.
- **Barcode register page**: list, add, rename and delete barcode → name
  entries (`/barcodes`, or the Barcodes tab in the app).
- **Items from a photo of a written list**: OCR, then a review step where you
  edit or drop lines before anything is saved. Web uses Tesseract on the
  server; Android uses ML Kit on the phone.
- **JSON import**: upload a list of `{name, quantity}` objects, up to 500 per
  file. There's a ready-made LLM prompt that turns a photo into that JSON, and
  the format is published at `/import-schema.json` and `/import-prompt.txt`.

## Android extras

- Works fully offline and stores everything on the phone (Room / SQLite).
- **Cloud sync** with a thingsFinder server, one group at a time:
  - Sync runs about 15 s after a change, hourly in the background, and on demand.
  - Conflicts are resolved per row: the newest edit wins.
  - Deletes sync both ways.
  - Switching groups first pushes any pending changes, then replaces the
    phone's places, boxes and items with the new group's. The barcode
    register is kept.
- **Backup / restore** to a JSON file, and export or import of the whole
  database file to move to a new phone.
- Deep links: `thingsfinder://box/…`, `thingsfinder://place/…`,
  `thingsfinder://join/…`.

## Configuration

Settings are read from `.env` in the project root (via `vlucas/phpdotenv`,
installed with `composer install`) or from real environment variables, which
take precedence: `TF_APP_URL`, `TF_REGISTRATION`, `TF_DB_PATH`,
`TF_BARCODE_LOOKUP`, `TF_TOKEN_TTL_DAYS`. Defaults and meanings are in
[.env.example](../.env.example) and the README.

## JSON API

A REST API mirrors the web app, plus the sync endpoint for the phone. It
lives in `api/` and runs either inside the web app under `/api`, or on its
own host (a vhost pointed at `api/`, or `composer serve:api`) with no `/api`
prefix — both at once if you like. Authenticate with the web session cookie or with
`Authorization: Bearer <token>` from `/api/auth/login` or
`/api/auth/register`. Token callers choose a group with `?group_id=`;
otherwise their default group is used. Full reference: [openapi.yaml](openapi.yaml).
