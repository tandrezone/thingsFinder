# thingsFinder Android — testing

## Automated

```bash
cd android
./gradlew assembleDebug lint testDebugUnitTest
```

| Suite | What it covers | Needs |
|---|---|---|
| `domain/*Test` | Slugs, JSON import parser, OCR line cleaning, box-link parsing, quantity/name rules | JVM only |
| `lookup/OpenBarcodeLookupTest` | Open Food Facts → UPCitemdb fallback, 5xx, malformed JSON, timeouts, no request for blank input (MockWebServer) | JVM only |
| `data/InventoryRepositoryTest` | slug de-dup, box move re-slug, barcode "known wins", item moves, cascade delete, search escaping, lookup toggle | Robolectric + in-memory Room |
| `data/BackupRepositoryTest` | export → restore round trip (share tokens kept), rejecting a non-backup file | Robolectric + in-memory Room |
| `sync/SyncEngineTest` | first/incremental push, tombstones sent then cleared (kept on failure), LWW on pull, server deletes, skipped rows retried, refused delete → full pull, 401 signs out | Robolectric + in-memory Room |
| `sync/OkHttpCloudApiTest` | login/sync wire format (snake_case, bearer header, nulls omitted), 401/404/HTML/disconnect mapping, server URL normalisation | JVM only (MockWebServer) |
| `ui/ViewModelTests` | Loading → Content / NotFound, Deleted event, add-item message, barcode Looking → Suggested, JSON review → add, OCR merge, search + SavedStateHandle | Robolectric + in-memory Room |

`@Preview`s for every key screen (phone + tablet, light + dark, and the
loading / empty / error / not-found variants) are in each `*Screen.kt`; open
them in Android Studio's Split/Design view.

## Manual checklist (device or emulator)

Run on a phone-sized emulator (API 24 and a current API level) and on a
tablet or foldable profile.

**First run & persistence**
- [ ] Fresh install opens on *Places* with the empty state and "Add a place".
- [ ] Add "Garage", a box "Tools bin", and items. Force-stop the app (Settings → Apps → Force stop) and reopen: everything is still there.

**Places / boxes / items**
- [ ] Rename a place to a name that already exists — it is accepted (slug becomes `name-2` internally), no crash.
- [ ] Delete a place with boxes and loose items — confirm dialog, then everything inside is gone (search no longer finds its items).
- [ ] Move a box to another place; open it from the new place.
- [ ] Move an item from a box to "(loose in …)" and back.
- [ ] Item quantity 1 shows no badge; 2+ shows "×N".

**Barcodes**
- [ ] Add item → Scan (code scanner downloads on first use; needs Play services). A new barcode + typed name adds the item and it appears in *Barcodes*.
- [ ] Scan the same barcode again in another box: name fills in and is locked ("Registered barcode").
- [ ] With network on, scan a grocery barcode not in the register: a suggested name appears ("Suggested by Open Food Facts").
- [ ] Turn off *Settings → Look up unknown barcodes online*; scan an unknown barcode: no suggestion, no network call.
- [ ] Airplane mode + unknown barcode: shows "Not registered yet" within a few seconds, never hangs.

**Photo (OCR) and JSON import**
- [ ] *From a photo → Take a photo* of a handwritten list: review sheet opens with one line per list line; edit one, remove one, *Add N items*.
- [ ] Close the review sheet without adding — the "N items waiting for review" card appears and reopens it. Rotate: still there.
- [ ] *Import JSON*: copy the prompt, use it with any LLM + photo, save the reply as `.json`, choose it — review sheet shows the items and any skipped entries.
- [ ] Choose a non-JSON file → "No items imported — That file isn't valid JSON."

**QR codes & labels**
- [ ] Box screen shows a QR code (white background even in dark theme). *Share label* opens the share sheet with a 50×30 mm PNG; *Share QR* shares the bare code.
- [ ] *Places → scan icon*: scan that QR from another screen/printout → opens the box.
- [ ] Scan the QR with the system camera app → offers to open thingsFinder → opens the box.
- [ ] Scan a random QR (e.g. a URL) → "That isn't a thingsFinder box label."

**Search**
- [ ] Search "glue" finds items in boxes and loose ones; tapping a result opens the right box/place.
- [ ] Searching `%` or `_` only matches names that literally contain them.
- [ ] Type a query, rotate, kill the process via Android Studio ("Terminate app" while in background) and return — the query is kept.

**Backup**
- [ ] *Export backup* to Downloads/Drive; add an item; *Restore from backup* → confirm → the extra item is gone, everything else is back; QR labels printed before still open their boxes.
- [ ] Restore a random `.json` → "That file isn't a thingsFinder backup.", nothing changes.

**Cloud sync** (needs the updated server code deployed)
- [ ] Fresh install: Settings shows *Sync to cloud* **on**, "Sign in to your thingsfinder.xyz account…".
- [ ] Wrong password → inline error, dialog stays open with the username kept. Airplane mode → "Couldn't reach thingsfinder.xyz".
- [ ] Sign in → "Syncing…" then "Last synced just now"; the phone's places appear on the website.
- [ ] Add an item on the phone, wait ~20 s (or *Sync now*) → it's on the website. Rename a place on the website → *Sync now* → renamed on the phone.
- [ ] Delete a box on the website → gone from the phone after a sync; delete an item on the phone → gone from the website.
- [ ] Airplane mode, make edits, turn network back on → they sync by themselves within a minute or so.
- [ ] Revoke the token (delete the row in `api_tokens` on the server) → next sync shows "signed out", sign in again works.
- [ ] Toggle *Sync to cloud* off → no requests (check the server log); back on → syncs.
- [ ] Sign out → data stays on the phone; sign in again → no duplicates.

**Database file**
- [ ] *Export database* → a `.sqlite` file that opens in DB Browser for SQLite with places/boxes/items.
- [ ] Add something, then *Import database* with that file → confirmation with counts → app restarts showing the file's contents.
- [ ] Import a random file / a JSON file / the server's `data/database.sqlite` → clear refusal, nothing changes.

**Platform behaviour**
- [ ] Rotation mid-form (add-item sheet, rename dialog, add-place dialog): typed text is kept.
- [ ] Back button: closes an open sheet/dialog first; otherwise returns to the previous screen; from another top-level tab goes back to *Places*, then exits. The box's "Box in …" chip goes up to its place.
- [ ] Keyboard: quantity fields show a number pad; barcode field "Search" key triggers lookup; the add-item sheet stays above the keyboard.
- [ ] Edge-to-edge: nothing hidden under the status bar, gesture bar or display cutout, in portrait and landscape.
- [ ] Tablet / landscape: navigation rail instead of bottom bar; lists are centred at a readable width.
- [ ] Dark theme (system setting): all screens readable, QR still scannable.
- [ ] Slow device (Android Studio → Emulator → Settings → throttle CPU): loading spinners show and resolve.
- [ ] TalkBack: every icon button announces an action ("Manage Garage", "Scan", "Back").

**Not applicable on Android** (web features intentionally not ported; see MIGRATION_PLAN.md): login/setup, logout, People/sharing, account switching, change password, expired/revoked tokens.
