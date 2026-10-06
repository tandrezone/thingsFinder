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

- **Cloud sync** — only after you sign in or create an account (Settings →
  Cloud sync). Default server `https://thingsfinder.xyz`, changeable in the
  sign-in / create-account dialog (https only). The server must run the
  updated PHP code in this repo (`includes/sync.php`, `POST /api/sync`, and
  `/api/groups` for groups).
- The optional barcode-name lookup (Open Food Facts / UPCitemdb), which can be
  turned off in Settings.

## Cloud sync — how it works

- Sign-in sends your password once to `POST /api/auth/login`; the phone keeps
  the returned token (in a DataStore file excluded from Android backups).
  Sign out revokes it on the server. *Create account* (`POST
  /api/auth/register`: username 3–40 of letters/digits/`._-`, password ≥ 8)
  works the same way once the server accepts it; "username taken" (409) and
  "registration disabled" (403) are shown in the dialog.
- Every row has a `uuid` and an `updated_at`. A sync sends what changed on the
  phone since the last push plus pending deletes, and gets back everything
  that changed on the server since the last cursor. **Conflicts: last write
  wins, per row** (same rule on both sides).
- Runs ~15 s after a local change, shortly after app start, and hourly in the
  background (WorkManager, only with a network). *Sync now* in Settings runs
  it immediately.
- Syncs **one group at a time** — the *active group* (see below), sent as
  `group_id`. Before the first sync it's whatever the server says is your
  default group.
- Known limits: deleting a barcode on the phone doesn't delete it on the
  server (it comes back); two devices editing the same row offline → the later
  edit wins; phone clocks that are badly wrong can win conflicts they
  shouldn't (the server rejects timestamps more than a day in the future).

## Groups

Every place on the server belongs to a group; all members share its places,
boxes and items (members with *view* permission can only look — the server
skips their changes with reason `read only`, and Settings says so). Each
account has at least its personal group, created at registration.

Settings → Cloud sync → *Groups* (when signed in) lists your groups and the
one on this phone, and lets you:

- **Switch** — first syncs, so pending changes reach the current group (if
  that fails, nothing happens); then wipes this phone's places, boxes, items
  and pending deletes in one transaction (the barcode register stays), sets
  the new active group and pulls its data.
- **Create** a group, or **join** one by scanning its invite QR, pasting the
  invite link (`https://host/join/{token}` or `thingsfinder://join/{token}`),
  or typing the group name + key (`K7F3-9QX2`, dash/case optional). After
  joining you're asked whether to switch to it.
- See the selected group's **invite** (QR, link, name, key; share / copy) and
  **members**; **leave** it. Owners can also rename, make a new invite (old
  link + key stop working), remove members and delete the group. Leaving or
  deleting the group on this phone switches to your default group first.

Signing out forgets the active group. Opening `thingsfinder://join/{token}`
opens Groups and offers to join.

## QR codes and links

Boxes and places each have a random share token (places since DB v3). The
phone's QR codes encode the cloud server's web pages (`https://host/view/{token}`,
`/add/{token}`, `/remove/{token}`, `/join/{token}` — the default server unless
another is set), which any camera can open; on a phone those pages offer an
"Open this in the app" link using the app's own scheme:

| Link | Opens |
|---|---|
| `thingsfinder://box/{token}` | the box |
| `thingsfinder://box/{token}/add`, `thingsfinder://place/{token}/add` | the box / place with the add-item sheet open |
| `thingsfinder://box/{token}/remove`, `thingsfinder://place/{token}/remove` | the box / place in remove mode (−1 or delete per item) |
| `thingsfinder://join/{token}` | Groups, offering to join |

The in-app scanner understands both: `https://host/view/{token}` (box),
`/add/{token}` and `/remove/{token}` (box or place — boxes are looked up
first) and `/join/{token}`, and the `thingsfinder://` links. Parsing lives in `domain/BoxLinks.kt`. Box and
place screens show *Add item* / *Remove item* codes, each shareable as a bare
QR or a printable label.

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
