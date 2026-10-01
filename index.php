<?php
/**
 * UI front controller.
 *
 * Public routes (no login):
 *   GET/POST  /login                          sign in
 *   GET/POST  /register                       create an account (unless TF_REGISTRATION=off)
 *   GET/POST  /setup                          create the first account (only until one exists)
 *   POST      /logout                         sign out
 *   GET       /view/{token}                    read-only box contents — what a box's QR code links to
 *
 * Everything else requires login, and is scoped to the active group (see
 * includes/auth.php and includes/groups.php):
 *   GET/POST  /                              home: list of places, create place
 *   GET       /search?q=...                  search within the active group
 *   GET/POST  /place/{placeSlug}              boxes + place-level items, create/rename/delete
 *   GET/POST  /place/{placeSlug}/{boxSlug}    items inside a box, create/rename/delete
 *   GET       /add/{token}, /remove/{token}   what a box's/place's add-item and remove-item QR codes
 *                                             link to: opens it ready to add or remove an item
 *   GET       /add|remove/{token}/qr.svg|png, label.svg|png   those QR codes and printable labels
 *   GET/POST  /barcodes                       the barcode -> item register: view, add, rename, remove
 *   POST      /switch-group                   switch which group you're looking at
 *   GET/POST  /groups                         your groups: create one, join one by name + key
 *   GET/POST  /groups/{id}                    a group's invite (link, QR, name + key) and members
 *   GET       /groups/{id}/invite.svg|png     the invite link as a QR code
 *   GET/POST  /join/{token}                   accept an invite link
 *   GET/POST  /account                        change your own password
 *
 * "Edit" permission in the active group is required for every POST to /,
 * /place/... and /barcodes; "view" permission is enough for every GET.
 */

require_once __DIR__ . '/includes/db.php';
require_once __DIR__ . '/includes/helpers.php';
require_once __DIR__ . '/includes/qrcode.php';
require_once __DIR__ . '/includes/auth.php';
require_once __DIR__ . '/includes/ocr.php';

$pdo = get_db();
$method = $_SERVER['REQUEST_METHOD'];
$path = current_path();
$segments = path_segments($path);

/** $nav is null for the public /view/{token} page (no account chrome shown); set once login is confirmed. */
function layout(string $title, string $body, array $breadcrumbs = [], ?array $nav = null): void
{
    $flash = flash_take();
    $flashIcon = ['success' => 'check', 'error' => 'alert', 'info' => 'inbox'][$flash['type'] ?? ''] ?? 'inbox';
    ?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#f6f5f2">
<title><?= h($title) ?> · thingsFinder</title>
<?= favicon_tags() ?>
<link rel="stylesheet" href="/assets/style.css">
</head>
<body>
<header class="topbar">
  <a class="brand" href="/"><span class="brand-mark">📦</span> thingsFinder</a>
  <?php if ($nav): ?>
    <details class="card-menu context-switcher">
      <summary aria-label="Switch group"><?= icon('users', 15) ?><span class="btn-label"><?= h($nav['groupName']) ?></span></summary>
      <div class="card-menu-body">
        <?php foreach ($nav['groups'] as $g): ?>
          <?php if ((int)$g['id'] === (int)$nav['groupId']) { continue; } ?>
          <form method="post" action="/switch-group" class="inline-form">
            <input type="hidden" name="group_id" value="<?= (int)$g['id'] ?>">
            <input type="hidden" name="next" value="/">
            <button type="submit" class="secondary"><?= h($g['name']) ?><?= $g['permission'] === 'view' ? ' · view only' : '' ?></button>
          </form>
        <?php endforeach; ?>
        <a class="btn secondary" href="/groups"><?= icon('users', 14) ?>Manage groups</a>
      </div>
    </details>
    <a class="btn btn-ghost topbar-link" href="/barcodes"><?= icon('barcode', 15) ?><span class="btn-label">Barcodes</span></a>
  <?php endif; ?>
  <form class="search-form" action="/search" method="get">
    <div class="search-wrap">
      <?= icon('search', 15) ?>
      <input type="search" name="q" placeholder="Search for an item…" value="<?= h($_GET['q'] ?? '') ?>" autocomplete="off">
    </div>
    <button type="submit"><?= icon('search', 15) ?><span class="btn-label">Search</span></button>
  </form>
  <?php if ($nav): ?>
    <details class="card-menu">
      <summary aria-label="Account menu"><?= icon('user', 15) ?><span class="btn-label"><?= h($nav['username']) ?></span></summary>
      <div class="card-menu-body">
        <a class="btn secondary" href="/groups"><?= icon('users', 14) ?>Groups</a>
        <a class="btn secondary" href="/account"><?= icon('lock', 14) ?>Account</a>
        <form method="post" action="/logout">
          <button type="submit" class="danger"><?= icon('log-out', 14) ?>Log out</button>
        </form>
      </div>
    </details>
  <?php endif; ?>
</header>
<main>
<?php if ($breadcrumbs): ?>
  <nav class="breadcrumbs">
    <a href="/">Home</a>
    <?php foreach ($breadcrumbs as $label => $url): ?>
      <?= icon('chevron', 13) ?>
      <?php if ($url): ?><a href="<?= h($url) ?>"><?= h($label) ?></a><?php else: ?><span><?= h($label) ?></span><?php endif; ?>
    <?php endforeach; ?>
  </nav>
<?php endif; ?>
<?php if ($flash): ?>
  <div class="flash flash-<?= h($flash['type']) ?>"><?= icon($flashIcon, 17) ?><span><?= h($flash['message']) ?></span></div>
<?php endif; ?>
<?= $body ?>
</main>
<footer>
  <small>JSON API available under <code>/api</code> (requires login) — e.g. <code>/api/search?q=glue</code></small>
</footer>
<script src="/assets/scan.js" defer></script>
<script src="/assets/copy.js" defer></script>
</body>
</html>
<?php
}

/** A stripped-down page shell for the public, no-login /view/{token} page — no search, no account menu, nothing personal. */
function layout_public(string $title, string $body): void
{
    ?><!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#f6f5f2">
<title><?= h($title) ?> · thingsFinder</title>
<?= favicon_tags() ?>
<link rel="stylesheet" href="/assets/style.css">
</head>
<body>
<header class="topbar">
  <span class="brand"><span class="brand-mark">📦</span> thingsFinder</span>
  <span class="meta">Shared read-only view</span>
</header>
<main>
<?= $body ?>
</main>
<footer>
  <small>You're viewing a read-only page shared via QR code or link — nothing here can be changed.</small>
</footer>
</body>
</html>
<?php
}

function render_error(int $status, string $message, ?array $nav = null): void
{
    http_response_code($status);
    layout('Error', '<div class="empty-state">' . icon('alert', 28) . '<p>' . h($message) . '</p></div>', [], $nav);
    exit;
}

/** The add/remove QR codes' landing URL for a box or place share token, e.g. https://host/add/{token}. */
function action_qr_url(string $action, string $token): string
{
    return base_url() . '/' . $action . '/' . $token;
}

/** 'add' or 'remove' while a box/place page was opened from one of its QR codes (?mode=, or carried through a POST); '' otherwise. */
function item_mode(): string
{
    $mode = (string)($_POST['mode'] ?? $_GET['mode'] ?? '');
    return in_array($mode, ['add', 'remove'], true) ? $mode : '';
}

function item_mode_query(string $mode): string
{
    return $mode !== '' ? '?mode=' . $mode . '#' . ($mode === 'add' ? 'add-item' : 'items') : '';
}

/**
 * The "Add item" and "Remove item" QR codes of a box or place ($kind),
 * with downloads for the code alone and a printable label. Scanning one
 * opens /add/{token} or /remove/{token}: the box/place, ready to add, or
 * to take something out. Only shown to members who can edit.
 */
function render_action_qr_section(string $token, string $kind, bool $canEdit): string
{
    if (!$canEdit) {
        return '';
    }
    ob_start();
    ?>
    <h2>Add &amp; remove codes</h2>
    <p class="meta">Stick these on the <?= $kind ?>: scanning one opens it straight at "add an item" or "take something out". They need a login with access to this group — the thingsFinder Android app understands them too.</p>
    <div class="qr-pair">
      <?php foreach (['add' => ['plus', 'Add an item'], 'remove' => ['trash', 'Remove an item']] as $action => [$iconName, $label]): ?>
        <?php $url = action_qr_url($action, $token); ?>
        <div class="qr-block">
          <div class="qr-image"><?= qrcode_svg_markup($url, 4) ?></div>
          <div class="qr-info">
            <p class="card-title"><?= icon($iconName, 15) ?> <?= $label ?></p>
            <p class="meta"><code><?= h($url) ?></code></p>
            <div class="row-actions">
              <a class="btn secondary" href="/<?= $action ?>/<?= h($token) ?>/qr.svg" download><?= icon('download', 14) ?>QR</a>
              <a class="btn secondary" href="/<?= $action ?>/<?= h($token) ?>/label.svg" download><?= icon('printer', 14) ?>Label SVG</a>
              <a class="btn secondary" href="/<?= $action ?>/<?= h($token) ?>/label.png" download><?= icon('printer', 14) ?>PNG</a>
            </div>
          </div>
        </div>
      <?php endforeach; ?>
    </div>
    <?php
    return (string)ob_get_clean();
}

/**
 * Shared handling for the item-related POST actions. Items can now live
 * directly in a place OR inside a box, so this is called from both the
 * place page and the box page — exactly one of $boxId/$placeId is non-null.
 * Returns true if $action was item-related (caller should stop looking at
 * its own actions and redirect); false otherwise. $groupId scopes
 * move_item's destination picker to the active group. Edits and deletes
 * only touch items that are in this very box/place.
 */
function handle_item_action(PDO $pdo, string $action, ?int $boxId, ?int $placeId, int $groupId): bool
{
    $inHere = $boxId !== null ? 'box_id = ' . $boxId : 'place_id = ' . (int)$placeId;
    if ($action === 'create_item') {
        $name = trim($_POST['name'] ?? '');
        $barcode = trim($_POST['barcode'] ?? '');
        $quantity = max(1, (int)($_POST['quantity'] ?? 1));
        $known = $barcode !== '' ? find_barcode($pdo, $barcode) : null;
        if ($known) {
            // A recognized barcode always wins — that's the whole point of scanning.
            $name = $known['name'];
        }
        if ($name === '' && $barcode !== '') {
            // Not in our own register — try a free, keyless product lookup
            // before giving up and asking the person to type a name.
            $suggestion = external_barcode_lookup($barcode);
            if ($suggestion) {
                pending_barcode_set($barcode);
                pending_name_set($suggestion['name']);
                flash_set(
                    'Found "' . $suggestion['name'] . '" (via ' . $suggestion['source'] . ') for that barcode — '
                    . 'check the name below and tap Add to save it.',
                    'info'
                );
            } else {
                pending_barcode_set($barcode);
                flash_set('That barcode isn\'t registered yet — type an item name so thingsFinder remembers it.', 'error');
            }
        } elseif ($name === '') {
            flash_set('Item name cannot be empty.', 'error');
        } else {
            $pdo->prepare('INSERT INTO items (box_id, place_id, name, quantity) VALUES (?, ?, ?, ?)')
                ->execute([$boxId, $placeId, $name, $quantity]);
            if ($barcode !== '' && !$known) {
                remember_barcode($pdo, $barcode, $name);
                flash_set('Item "' . $name . '" added — barcode remembered for next time.', 'success');
            } elseif ($known) {
                flash_set('Recognized barcode — added "' . $name . '".', 'success');
            } else {
                flash_set('Item "' . $name . '" added.', 'success');
            }
        }
        return true;
    }
    if ($action === 'rename_item') {
        $id = (int)($_POST['id'] ?? 0);
        $name = trim($_POST['name'] ?? '');
        $quantity = max(1, (int)($_POST['quantity'] ?? 1));
        if ($id && $name !== '') {
            $pdo->prepare("UPDATE items SET name = ?, quantity = ? WHERE id = ? AND $inHere")->execute([$name, $quantity, $id]);
            flash_set('Item updated.', 'success');
        }
        return true;
    }
    if ($action === 'delete_item') {
        $id = (int)($_POST['id'] ?? 0);
        if ($id) {
            $pdo->prepare("DELETE FROM items WHERE id = ? AND $inHere")->execute([$id]);
            flash_set('Item deleted.', 'success');
        }
        return true;
    }
    // "Take one out" from the remove-item QR flow: one less, or gone at the last one.
    if ($action === 'take_one') {
        $id = (int)($_POST['id'] ?? 0);
        $stmt = $pdo->prepare("SELECT name, quantity FROM items WHERE id = ? AND $inHere");
        $stmt->execute([$id]);
        $item = $stmt->fetch();
        if ($item && (int)$item['quantity'] > 1) {
            $pdo->prepare('UPDATE items SET quantity = quantity - 1 WHERE id = ?')->execute([$id]);
            flash_set('Took one "' . $item['name'] . '" out — ' . ((int)$item['quantity'] - 1) . ' left.', 'success');
        } elseif ($item) {
            $pdo->prepare('DELETE FROM items WHERE id = ?')->execute([$id]);
            flash_set('Removed "' . $item['name'] . '" — that was the last one.', 'success');
        }
        return true;
    }
    if ($action === 'move_item') {
        $id = (int)($_POST['id'] ?? 0);
        $destination = (string)($_POST['destination'] ?? '');
        if ($id && move_item_to($pdo, $id, $groupId, $destination)) {
            flash_set('Item moved.', 'success');
        } else {
            flash_set('Couldn\'t move that item — pick a valid destination.', 'error');
        }
        return true;
    }
    if ($action === 'import_json') {
        if (!isset($_FILES['import']) || $_FILES['import']['error'] === UPLOAD_ERR_NO_FILE) {
            flash_set('Choose a JSON file first.', 'error');
            return true;
        }
        if ($_FILES['import']['error'] !== UPLOAD_ERR_OK) {
            flash_set('That upload didn\'t go through — try again.', 'error');
            return true;
        }
        $raw = (string)file_get_contents($_FILES['import']['tmp_name']);
        // LLMs like to wrap JSON in ```json fences even when told not to, and
        // a copy-paste often picks up a stray BOM — strip both rather than
        // making the person hand-edit the file.
        $raw = preg_replace('/^\xEF\xBB\xBF/', '', trim($raw));
        $raw = preg_replace('/^```(?:json)?\s*|\s*```$/i', '', (string)$raw);
        $decoded = json_decode((string)$raw, true);
        if ($decoded === null && json_last_error() !== JSON_ERROR_NONE) {
            flash_set('That file isn\'t valid JSON: ' . json_last_error_msg() . '.', 'error');
            return true;
        }
        [$rows, $problems] = import_parse_items($decoded);
        if (!$rows) {
            flash_set('No items imported — ' . ($problems ? strtolower($problems[0]) : 'that file had no usable entries.'), 'error');
            return true;
        }
        $stmt = $pdo->prepare('INSERT INTO items (box_id, place_id, name, quantity) VALUES (?, ?, ?, ?)');
        $pdo->beginTransaction();
        try {
            foreach ($rows as $row) {
                $stmt->execute([$boxId, $placeId, $row['name'], $row['quantity']]);
            }
            $pdo->commit();
        } catch (Throwable $e) {
            $pdo->rollBack();
            flash_set('Something went wrong writing those items — nothing was imported.', 'error');
            return true;
        }
        $added = count($rows);
        $message = 'Imported ' . $added . ' item' . ($added === 1 ? '' : 's') . '.';
        if ($problems) {
            $message .= ' Skipped ' . count($problems) . ' bad entr' . (count($problems) === 1 ? 'y' : 'ies') . ': '
                . implode(' ', array_slice($problems, 0, 3));
        }
        flash_set($message, $problems ? 'error' : 'success');
        return true;
    }
    return false;
}

/**
 * Shared handling for the "add items from a photo" POST actions (upload,
 * edit a candidate line, remove a line, discard). scan_add_all — the one
 * action that actually writes items — is handled by the caller since it
 * needs $boxId/$placeId, which this function doesn't take.
 */
function handle_scan_action(string $action, string $containerKey): bool
{
    if ($action === 'scan_upload') {
        if (!isset($_FILES['photo']) || $_FILES['photo']['error'] === UPLOAD_ERR_NO_FILE) {
            flash_set('Choose or take a photo first.', 'error');
            return true;
        }
        if ($_FILES['photo']['error'] !== UPLOAD_ERR_OK) {
            flash_set('That upload didn\'t go through — try again.', 'error');
            return true;
        }
        try {
            $found = ocr_extract_items($_FILES['photo']['tmp_name'], $_FILES['photo']['name']);
        } catch (RuntimeException $e) {
            flash_set($e->getMessage(), 'error');
            return true;
        }
        if (!$found) {
            flash_set('Couldn\'t find any readable text in that photo — try a clearer, well-lit, straight-on shot of the list.', 'error');
            return true;
        }
        $existing = ocr_review_get($containerKey);
        $seen = array_map(fn($i) => mb_strtolower($i['name'], 'UTF-8'), $existing);
        $added = 0;
        foreach ($found as $item) {
            if (!in_array(mb_strtolower($item['name'], 'UTF-8'), $seen, true)) {
                $existing[] = $item;
                $added++;
            }
        }
        ocr_review_set($containerKey, $existing);
        flash_set('Found ' . $added . ' line(s) — review them below, then add what looks right.', 'success');
        return true;
    }
    if ($action === 'scan_update_line') {
        $items = ocr_review_get($containerKey);
        $i = (int)($_POST['index'] ?? -1);
        $name = trim($_POST['name'] ?? '');
        $qty = max(1, (int)($_POST['quantity'] ?? 1));
        if (isset($items[$i]) && $name !== '') {
            $items[$i] = ['name' => $name, 'quantity' => $qty];
            ocr_review_set($containerKey, $items);
            flash_set('Line updated.', 'success');
        }
        return true;
    }
    if ($action === 'scan_remove_line') {
        $items = ocr_review_get($containerKey);
        $i = (int)($_POST['index'] ?? -1);
        if (isset($items[$i])) {
            array_splice($items, $i, 1);
            ocr_review_set($containerKey, $items);
        }
        return true;
    }
    if ($action === 'scan_cancel') {
        ocr_review_clear($containerKey);
        flash_set('Discarded.', 'info');
        return true;
    }
    return false;
}

/**
 * The "Import items from JSON" tile: the prompt to hand an LLM together with
 * a photo, the schema that prompt produces, and the upload form that eats it.
 *
 * All three are shown in one place on purpose — the whole flow is
 * copy prompt -> paste into any LLM with a photo -> save the reply -> upload
 * it here, and it only reads as one flow if you can see each step.
 */
function render_json_import_section(string $containerLabel = ''): string
{
    $prompt = import_prompt($containerLabel);
    ob_start();
    ?>
    <div class="card add-card import-card">
      <details>
        <summary><?= icon('upload', 15) ?>Import items from JSON</summary>

        <ol class="import-steps">
          <li>Copy the prompt below into ChatGPT, Claude, Gemini — whichever you use — and attach a photo of your stuff.</li>
          <li>Save its reply as a <code>.json</code> file.</li>
          <li>Upload that file here and every item lands in <?= $containerLabel !== '' ? h($containerLabel) : 'this container' ?>.</li>
        </ol>

        <div class="import-prompt">
          <div class="import-prompt-head">
            <span class="import-prompt-title"><?= icon('sparkle', 14) ?>Prompt for your LLM</span>
            <div class="row-actions">
              <button type="button" class="secondary copy-btn" data-copy-target="import-prompt-text"><?= icon('copy', 14) ?><span class="copy-btn-label">Copy prompt</span></button>
              <a class="btn secondary" href="/import-prompt.txt" download="thingsfinder-photo-prompt.txt"><?= icon('download', 14) ?>.txt</a>
            </div>
          </div>
          <textarea id="import-prompt-text" class="import-prompt-text" rows="10" readonly spellcheck="false" aria-label="Prompt to copy into an LLM"><?= h($prompt) ?></textarea>
          <p class="copy-status meta" role="status" aria-live="polite"></p>
        </div>

        <details class="import-schema">
          <summary>The JSON format it should reply with</summary>
          <p class="meta">
            An array of objects. <code>name</code> is required; <code>quantity</code> is an
            optional integer that defaults to <code>1</code>. Up to <?= IMPORT_MAX_ITEMS ?> items
            per file. An object wrapper — <code>{"items": [&hellip;]}</code> — works too.
          </p>
          <p class="import-schema-label">Example file</p>
          <pre class="import-code"><code><?= h(import_json_pretty(import_example_items())) ?></code></pre>
          <p class="import-schema-label">JSON Schema</p>
          <pre class="import-code import-code-tall"><code><?= h(import_json_pretty(import_item_schema())) ?></code></pre>
          <p class="meta">
            Machine-readable copies: <a href="/import-schema.json" target="_blank" rel="noopener">/import-schema.json</a>
            &middot; <a href="/import-prompt.txt" target="_blank" rel="noopener">/import-prompt.txt</a>
          </p>
        </details>

        <form method="post" enctype="multipart/form-data" class="import-form">
          <input type="hidden" name="action" value="import_json">
          <input type="file" name="import" accept=".json,application/json,text/plain" required>
          <button type="submit"><?= icon('upload', 14) ?>Import</button>
        </form>
      </details>
    </div>
    <?php
    return (string)ob_get_clean();
}

/**
 * Renders the "Items" section (card list + add-item tile with barcode
 * scanning) shared by the box page and the place page. Management
 * controls (rename/delete/move/add) only appear when $canEdit is true.
 * $moveDestinations is the list_move_destinations() shape (places, each
 * with a nested `boxes` array) used to build each item's move-to picker —
 * pass [] when $canEdit is false, since it's unused then.
 * $containerLabel ("Kitchen / Drawer 2") is only used to give the copyable
 * photo-import prompt a bit of context; blank is fine.
 * $mode comes from the add-item / remove-item QR codes: 'add' opens the
 * add-item tile ready to type or scan; 'remove' puts a "take one out" and
 * a "remove" button on every item, with $exitUrl leading back to normal.
 */
function render_items_section(array $items, string $pendingBarcode, string $pendingName, bool $canEdit, array $moveDestinations = [], string $containerLabel = '', string $mode = '', string $exitUrl = ''): string
{
    $removing = $mode === 'remove' && $canEdit;
    $adding = $mode === 'add' && $canEdit;
    ob_start();
    ?>
    <h2 id="items">Items</h2>
    <?php if ($removing): ?>
      <div class="flash flash-info mode-banner"><?= icon('trash', 17) ?><span>Taking something out? Tap <strong>−1</strong> on it, or <strong>Remove</strong> to take all of them. <a href="<?= h($exitUrl) ?>">Done</a></span></div>
    <?php endif; ?>
    <?php if (!$items): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>No items here yet.</p>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($items as $it): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($it['name']) ?></span>
              <?php if ((int)$it['quantity'] > 1): ?><span class="qty-badge">×<?= (int)$it['quantity'] ?></span><?php endif; ?>
            </div>
            <?php if ($removing): ?>
            <div class="row-actions">
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="take_one">
                <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                <input type="hidden" name="mode" value="remove">
                <button type="submit" class="secondary" aria-label="Take one <?= h($it['name']) ?> out">−1</button>
              </form>
              <form method="post" class="inline-form" onsubmit="return confirm('Remove <?= h(addslashes($it['name'])) ?>?');">
                <input type="hidden" name="action" value="delete_item">
                <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                <input type="hidden" name="mode" value="remove">
                <button type="submit" class="danger"><?= icon('trash', 14) ?>Remove</button>
              </form>
            </div>
            <?php elseif ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage item"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <input type="text" name="name" value="<?= h($it['name']) ?>" required>
                  <input type="number" name="quantity" value="<?= (int)$it['quantity'] ?>" min="1" class="qty-input" aria-label="Quantity">
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Save</button>
                </form>
                <?php if ($moveDestinations): ?>
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="move_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <select name="destination" aria-label="Move to">
                    <?php foreach ($moveDestinations as $place): ?>
                      <optgroup label="<?= h($place['name']) ?>">
                        <option value="place:<?= (int)$place['id'] ?>" <?= ($it['box_id'] === null && (int)$it['place_id'] === (int)$place['id']) ? 'selected' : '' ?>>
                          (loose in <?= h($place['name']) ?>)
                        </option>
                        <?php foreach ($place['boxes'] as $b): ?>
                          <option value="box:<?= (int)$b['id'] ?>" <?= ((int)($it['box_id'] ?? 0) === (int)$b['id']) ? 'selected' : '' ?>>
                            <?= h($b['name']) ?>
                          </option>
                        <?php endforeach; ?>
                      </optgroup>
                    <?php endforeach; ?>
                  </select>
                  <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
                </form>
                <?php endif; ?>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this item?');">
                  <input type="hidden" name="action" value="delete_item">
                  <input type="hidden" name="id" value="<?= (int)$it['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit && !$removing): ?>
      <li class="card add-card" id="add-item">
        <details<?= ($adding || $pendingBarcode !== '' || $pendingName !== '') ? ' open' : '' ?>>
          <summary><?= icon('plus', 15) ?>Add an item</summary>
          <form method="post" class="add-item-form">
            <input type="hidden" name="action" value="create_item">
            <?php if ($adding): ?><input type="hidden" name="mode" value="add"><?php endif; ?>
            <div class="barcode-row">
              <input type="text" name="barcode" value="<?= h($pendingBarcode) ?>" placeholder="Barcode — scan or type" autocomplete="off" inputmode="numeric">
              <button type="button" class="secondary scan-btn" hidden><?= icon('camera', 14) ?>Scan</button>
            </div>
            <p class="scan-support-note meta" hidden></p>
            <p class="scan-hint meta" hidden></p>
            <div class="name-qty-row">
              <input type="text" name="name" value="<?= h($pendingName) ?>" placeholder="e.g. Hot glue gun" class="name-input"<?= $adding ? ' autofocus' : '' ?>>
              <input type="number" name="quantity" value="1" min="1" class="qty-input" title="Quantity" aria-label="Quantity">
            </div>
            <button type="submit">Add</button>
          </form>
          <div class="scanner-overlay" hidden>
            <video playsinline muted></video>
            <button type="button" class="btn btn-ghost scan-cancel">Cancel</button>
          </div>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php if ($canEdit && !$removing): ?>
    <p class="meta">Know a barcode already? Scan it and thingsFinder either adds the item it remembers, or checks free barcode databases for a name to suggest — confirm or edit it once and it's remembered for next time. Manage all associations on the <a href="/barcodes">barcode register</a>.</p>
    <?= render_json_import_section($containerLabel) ?>
    <?php endif; ?>
    <?php
    return ob_get_clean();
}

/**
 * Renders either the "add items from a photo" upload tile, or — while a
 * batch of OCR'd candidate lines is pending — the editable review list for
 * that batch. Returns '' entirely when $canEdit is false.
 */
function render_photo_scan_section(string $containerKey, array $reviewItems, bool $canEdit): string
{
    if (!$canEdit) {
        return '';
    }
    ob_start();
    if ($reviewItems) {
        ?>
        <h2>Review items from photo</h2>
        <p class="meta">Edit a line and tap Save, or remove it — then add whatever's left.</p>
        <ul class="scan-review-list">
          <?php foreach ($reviewItems as $i => $ri): ?>
            <li class="card">
              <div class="card-head">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="scan_update_line">
                  <input type="hidden" name="index" value="<?= (int)$i ?>">
                  <input type="text" name="name" value="<?= h($ri['name']) ?>" required class="name-input">
                  <input type="number" name="quantity" value="<?= (int)$ri['quantity'] ?>" min="1" class="qty-input" aria-label="Quantity">
                  <button type="submit" class="secondary"><?= icon('check', 14) ?>Save</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this line?');">
                  <input type="hidden" name="action" value="scan_remove_line">
                  <input type="hidden" name="index" value="<?= (int)$i ?>">
                  <button type="submit" class="danger" aria-label="Remove"><?= icon('trash', 14) ?></button>
                </form>
              </div>
            </li>
          <?php endforeach; ?>
        </ul>
        <div class="row-actions">
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="scan_add_all">
            <button type="submit"><?= icon('check', 14) ?>Add <?= count($reviewItems) ?> item<?= count($reviewItems) === 1 ? '' : 's' ?></button>
          </form>
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="scan_cancel">
            <button type="submit" class="btn-ghost">Discard all</button>
          </form>
        </div>
        <?php
    } else {
        ?>
        <div class="card add-card photo-scan-card">
          <details>
            <summary><?= icon('camera', 15) ?>Add items from a photo</summary>
            <?php if (!ocr_available()): ?>
              <p class="meta scan-support-note">OCR isn't set up on this server — it needs the <code>tesseract</code> command-line program installed and PHP's <code>shell_exec()</code> enabled. See the README for install instructions.</p>
            <?php else: ?>
              <form method="post" enctype="multipart/form-data">
                <input type="hidden" name="action" value="scan_upload">
                <p class="meta">Take or upload a photo of a printed/handwritten list (not a photo of the items themselves) — thingsFinder reads the text and lets you review it before anything is added.</p>
                <input type="file" name="photo" accept="image/*" capture="environment" required>
                <button type="submit"><?= icon('camera', 14) ?>Extract items</button>
              </form>
            <?php endif; ?>
          </details>
        </div>
        <?php
    }
    return ob_get_clean();
}

// -------------------------------------------------------------------------
// Routes: /import-schema.json and /import-prompt.txt — the contract for the
// "Import items from JSON" feature. Public and static: they describe the file
// format, they don't touch anyone's data, and being fetchable by URL means a
// person (or a tool) can point an LLM straight at them.
// -------------------------------------------------------------------------
if ($segments === ['import-schema.json']) {
    header('Content-Type: application/schema+json; charset=utf-8');
    header('Cache-Control: public, max-age=3600');
    echo import_json_pretty(import_item_schema()), "\n";
    exit;
}
if ($segments === ['import-prompt.txt']) {
    header('Content-Type: text/plain; charset=utf-8');
    header('Cache-Control: public, max-age=3600');
    echo import_prompt(), "\n";
    exit;
}

// -------------------------------------------------------------------------
// Route: /view/{token} — public, read-only, no login. What a box's QR
// code / printable label links to.
// -------------------------------------------------------------------------
if (count($segments) === 2 && $segments[0] === 'view') {
    $box = find_box_by_token($pdo, $segments[1]);
    if (!$box) {
        http_response_code(404);
        layout_public('Not found', '<div class="empty-state">' . icon('alert', 28) . '<p>This link is invalid or no longer exists.</p></div>');
        exit;
    }
    $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ?');
    $stmt->execute([(int)$box['place_id']]);
    $place = $stmt->fetch();

    $stmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
    $stmt->execute([(int)$box['id']]);
    $items = $stmt->fetchAll();

    ob_start();
    ?>
    <h1><?= h($box['name']) ?></h1>
    <p class="meta">In <?= h($place['name'] ?? 'a place') ?> · read-only</p>
    <?php if (!$items): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>This box is empty.</p>
      </div>
    <?php else: ?>
      <ul class="card-list">
        <?php foreach ($items as $it): ?>
          <li class="card">
            <div class="card-body">
              <span class="card-title"><?= h($it['name']) ?></span>
              <?php if ((int)$it['quantity'] > 1): ?><span class="qty-badge">×<?= (int)$it['quantity'] ?></span><?php endif; ?>
            </div>
          </li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>
    <?php
    layout_public($box['name'], ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /setup — create the first account. Only usable before any
// account exists; once one does, this always bounces to /login.
// -------------------------------------------------------------------------
if ($segments === ['setup']) {
    if (has_any_users($pdo)) {
        redirect('/login');
    }
    $error = '';
    if ($method === 'POST') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $confirm = (string)($_POST['password_confirm'] ?? '');
        if ($password !== $confirm) {
            $error = 'Passwords don\'t match.';
        } elseif (($problem = validate_new_account($pdo, $username, $password)) !== null) {
            $error = $problem;
        } else {
            [$userId, $groupId] = register_user($pdo, $username, $password);
            adopt_orphan_places($pdo, $groupId); // upgrading from a version with no login: your existing places become yours
            login_user($userId);
            flash_set('Welcome to thingsFinder!', 'success');
            redirect('/');
        }
    }
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Set up thingsFinder</h1>
      <p class="page-subtitle">Create the first account. You can invite others into your group later, from Groups.</p>
      <?php if ($error): ?><p class="flash flash-error"><?= icon('alert', 16) ?><span><?= h($error) ?></span></p><?php endif; ?>
      <form method="post" class="stack-form-v">
        <label>Username<input type="text" name="username" value="<?= h($_POST['username'] ?? '') ?>" autofocus required></label>
        <label>Password<input type="password" name="password" required minlength="8"></label>
        <label>Confirm password<input type="password" name="password_confirm" required minlength="8"></label>
        <button type="submit">Create account</button>
      </form>
    </div>
    <?php
    layout_public('Set up', ob_get_clean());
    exit;
}

/** Where to go after logging in or registering: a local path from ?next=/POST next, else home. */
function safe_next(string $next): string
{
    return ($next !== '' && $next[0] === '/' && substr($next, 0, 2) !== '//') ? $next : '/';
}

// -------------------------------------------------------------------------
// Route: /login
// -------------------------------------------------------------------------
if ($segments === ['login']) {
    if (!has_any_users($pdo)) {
        redirect('/setup');
    }
    if (current_user_id() !== null) {
        redirect('/');
    }
    $error = '';
    if ($method === 'POST') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $user = find_user_by_username($pdo, $username);
        if ($user && password_verify($password, $user['password_hash'])) {
            login_user((int)$user['id']);
            redirect(safe_next((string)($_POST['next'] ?? '/')));
        }
        $error = 'Wrong username or password.';
    }
    $next = (string)($_GET['next'] ?? $_POST['next'] ?? '');
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Log in</h1>
      <?php if ($error): ?><p class="flash flash-error"><?= icon('alert', 16) ?><span><?= h($error) ?></span></p><?php endif; ?>
      <form method="post" class="stack-form-v">
        <input type="hidden" name="next" value="<?= h($next) ?>">
        <label>Username<input type="text" name="username" value="<?= h($_POST['username'] ?? '') ?>" autofocus required></label>
        <label>Password<input type="password" name="password" required></label>
        <button type="submit">Log in</button>
      </form>
      <?php if (registration_enabled()): ?>
        <p class="meta auth-switch">New here? <a href="/register<?= $next !== '' ? '?next=' . rawurlencode($next) : '' ?>">Create an account</a></p>
      <?php endif; ?>
    </div>
    <?php
    layout_public('Log in', ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /register — open sign-up. Every new account gets its own personal
// group; an invite link (?next=/join/...) carries through so they land in
// the group they were invited to.
// -------------------------------------------------------------------------
if ($segments === ['register']) {
    if (!has_any_users($pdo)) {
        redirect('/setup');
    }
    if (current_user_id() !== null) {
        redirect('/');
    }
    $next = (string)($_GET['next'] ?? $_POST['next'] ?? '');
    if (!registration_enabled()) {
        flash_set('Sign-up is turned off on this server — ask its admin for an account.', 'error');
        redirect('/login' . ($next !== '' ? '?next=' . rawurlencode($next) : ''));
    }
    $error = '';
    if ($method === 'POST') {
        $username = trim($_POST['username'] ?? '');
        $password = (string)($_POST['password'] ?? '');
        $confirm = (string)($_POST['password_confirm'] ?? '');
        if ($password !== $confirm) {
            $error = 'Passwords don\'t match.';
        } elseif (($problem = validate_new_account($pdo, $username, $password)) !== null) {
            $error = $problem;
        } else {
            [$userId] = register_user($pdo, $username, $password);
            login_user($userId);
            flash_set('Welcome to thingsFinder, ' . $username . '!', 'success');
            redirect(safe_next($next));
        }
    }
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Create an account</h1>
      <p class="page-subtitle">You'll get your own group for your stuff, and can join other people's groups with an invite.</p>
      <?php if ($error): ?><p class="flash flash-error"><?= icon('alert', 16) ?><span><?= h($error) ?></span></p><?php endif; ?>
      <form method="post" class="stack-form-v">
        <input type="hidden" name="next" value="<?= h($next) ?>">
        <label>Username<input type="text" name="username" value="<?= h($_POST['username'] ?? '') ?>" autofocus required minlength="3" maxlength="40" pattern="[A-Za-z0-9._\-]+" autocomplete="username"></label>
        <label>Password<input type="password" name="password" required minlength="8" autocomplete="new-password"></label>
        <label>Confirm password<input type="password" name="password_confirm" required minlength="8" autocomplete="new-password"></label>
        <button type="submit">Create account</button>
      </form>
      <p class="meta auth-switch">Already have one? <a href="/login<?= $next !== '' ? '?next=' . rawurlencode($next) : '' ?>">Log in</a></p>
    </div>
    <?php
    layout_public('Create account', ob_get_clean());
    exit;
}

// -------------------------------------------------------------------------
// Route: /logout
// -------------------------------------------------------------------------
if ($segments === ['logout']) {
    if ($method === 'POST') {
        logout_user();
    }
    redirect('/login');
}

// -------------------------------------------------------------------------
// Everything below requires a logged-in session.
// -------------------------------------------------------------------------
if (!has_any_users($pdo)) {
    redirect('/setup');
}
if (current_user_id() === null && count($segments) === 2 && $segments[0] === 'join') {
    // An invite link opened by someone without an account yet: let them sign up first.
    redirect((registration_enabled() ? '/register' : '/login') . '?next=' . rawurlencode('/join/' . $segments[1]));
}
require_login();

$currentUser = current_user($pdo);
if (!$currentUser) {
    // Session pointed at a user that no longer exists (deleted account).
    logout_user();
    redirect('/login');
}
$userId = (int)$currentUser['id'];
$groupId = active_group_id($pdo);
$group = find_membership($pdo, $groupId, $userId);
$canEdit = can_edit($pdo);
$nav = [
    'userId' => $userId,
    'username' => $currentUser['username'],
    'groupId' => $groupId,
    'groupName' => $group['name'],
    'groups' => list_user_groups($pdo, $userId),
];

// -------------------------------------------------------------------------
// Route: /switch-group
// -------------------------------------------------------------------------
if ($segments === ['switch-group']) {
    if ($method === 'POST') {
        $requested = (int)($_POST['group_id'] ?? 0);
        if ($requested && set_active_group($pdo, $requested)) {
            flash_set('Switched to "' . find_membership($pdo, $requested, $userId)['name'] . '".', 'success');
        } else {
            flash_set('You\'re not in that group.', 'error');
        }
    }
    redirect(safe_next((string)($_POST['next'] ?? '/')));
}

// The old sharing page lives on as groups.
if ($segments === ['people']) {
    redirect('/groups');
}

// -------------------------------------------------------------------------
// Route: /join/{token} — accept an invite link (or its QR code)
// -------------------------------------------------------------------------
if (count($segments) === 2 && $segments[0] === 'join') {
    $invited = find_group_by_invite_token($pdo, $segments[1]);
    if (!$invited) {
        render_error(404, 'This invite link is invalid or was replaced by a new one — ask for a fresh invite.', $nav);
    }
    $invitedId = (int)$invited['id'];
    if (find_membership($pdo, $invitedId, $userId)) {
        set_active_group($pdo, $invitedId);
        flash_set('You\'re already in "' . $invited['name'] . '".', 'info');
        redirect('/');
    }
    if ($method === 'POST') {
        add_group_member($pdo, $invitedId, $userId);
        set_active_group($pdo, $invitedId);
        flash_set('You joined "' . $invited['name'] . '" — everything in it is shared with you now.', 'success');
        redirect('/');
    }
    $memberCount = count(list_group_members($pdo, $invitedId));
    ob_start();
    ?>
    <div class="auth-card">
      <h1>Join "<?= h($invited['name']) ?>"?</h1>
      <p class="page-subtitle">You've been invited to a group with <?= $memberCount ?> member<?= $memberCount === 1 ? '' : 's' ?>. Everyone in it can see, add, edit and remove its places, boxes and items.</p>
      <form method="post" class="stack-form-v">
        <button type="submit"><?= icon('users', 14) ?>Join group</button>
      </form>
      <p class="meta auth-switch"><a href="/">Not now</a></p>
    </div>
    <?php
    layout('Join group', ob_get_clean(), ['Join group' => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /groups — every group you're in; create one, or join one by its
// name + key (the typed-in alternative to an invite link)
// -------------------------------------------------------------------------
if ($segments === ['groups']) {
    if ($method === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'create_group') {
            $name = clean_group_name($_POST['name'] ?? '');
            if ($name === '') {
                flash_set('Give the group a name.', 'error');
                redirect('/groups');
            }
            $newId = create_group($pdo, $userId, $name);
            set_active_group($pdo, $newId);
            flash_set('Group "' . $name . '" created — invite people from here.', 'success');
            redirect('/groups/' . $newId);
        }
        if ($action === 'join_group') {
            $found = find_group_by_name_and_key($pdo, (string)($_POST['name'] ?? ''), (string)($_POST['key'] ?? ''));
            if (!$found) {
                usleep(300000); // keys are short — slow down guessing a little
                flash_set('No group matches that name and key — check both with whoever invited you.', 'error');
                redirect('/groups');
            }
            add_group_member($pdo, (int)$found['id'], $userId);
            set_active_group($pdo, (int)$found['id']);
            flash_set('You joined "' . $found['name'] . '".', 'success');
            redirect('/');
        }
        redirect('/groups');
    }

    ob_start();
    ?>
    <h1>Groups</h1>
    <p class="page-subtitle">Everyone in a group shares its places, boxes and items — and can add, edit and remove them.</p>
    <ul class="card-list">
      <?php foreach ($nav['groups'] as $g): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <a class="card-title" href="/groups/<?= (int)$g['id'] ?>"><?= h($g['name']) ?></a>
              <span class="meta">
                <?= (int)$g['member_count'] ?> member<?= (int)$g['member_count'] === 1 ? '' : 's' ?>
                · <?= $g['role'] === 'owner' ? 'you own it' : ($g['permission'] === 'view' ? 'view only' : 'member') ?>
                <?php if ((int)$g['id'] === $groupId): ?> · <strong>current</strong><?php endif; ?>
              </span>
            </div>
            <?php if ((int)$g['id'] !== $groupId): ?>
            <form method="post" action="/switch-group" class="inline-form">
              <input type="hidden" name="group_id" value="<?= (int)$g['id'] ?>">
              <button type="submit" class="secondary">Open</button>
            </form>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Create a group</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_group">
            <input type="text" name="name" placeholder="e.g. Family, Workshop" required maxlength="<?= GROUP_NAME_MAX_LENGTH ?>">
            <button type="submit">Create</button>
          </form>
        </details>
      </li>
      <li class="card add-card">
        <details>
          <summary><?= icon('users', 15) ?>Join a group with its name and key</summary>
          <form method="post" class="stack-form-v">
            <input type="hidden" name="action" value="join_group">
            <label>Group name<input type="text" name="name" required></label>
            <label>Key<input type="text" name="key" placeholder="XXXX-XXXX" required autocomplete="off" autocapitalize="characters" spellcheck="false"></label>
            <button type="submit">Join</button>
          </form>
          <p class="meta">Got a link or QR code instead? Just open it.</p>
        </details>
      </li>
    </ul>
    <?php
    layout('Groups', ob_get_clean(), ['Groups' => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /groups/{id} — one group: its invite and members. Owners can
// rename it, replace the invite, change or remove members, and delete it.
// -------------------------------------------------------------------------
if (count($segments) >= 2 && $segments[0] === 'groups') {
    $shownId = (int)$segments[1];
    $shown = find_membership($pdo, $shownId, $userId);
    if (!$shown) {
        render_error(404, 'You\'re not in that group.', $nav);
    }
    $isOwner = $shown['role'] === 'owner';
    $inviteUrl = group_invite_url($shown);

    // /groups/{id}/invite.svg | invite.png — the invite link as a QR code
    if (count($segments) === 3 && in_array($segments[2], ['invite.svg', 'invite.png'], true)) {
        if ($shown['permission'] !== 'edit') {
            render_error(403, 'Only members who can edit can invite people.', $nav);
        }
        $segments[2] === 'invite.png' ? qrcode_send_png($inviteUrl) : qrcode_send_svg($inviteUrl);
        exit;
    }
    if (count($segments) !== 2) {
        render_error(404, 'Page not found.', $nav);
    }

    if ($method === 'POST') {
        $action = $_POST['action'] ?? '';
        if ($action === 'leave_group' && !$isOwner) {
            remove_group_member($pdo, $shownId, $userId);
            flash_set('You left "' . $shown['name'] . '".', 'success');
            redirect('/groups');
        }
        if (!$isOwner) {
            flash_set('Only the group\'s owner can do that.', 'error');
            redirect('/groups/' . $shownId);
        }
        if ($action === 'rename_group') {
            $name = clean_group_name($_POST['name'] ?? '');
            if ($name !== '') {
                rename_group($pdo, $shownId, $name);
                flash_set('Group renamed.', 'success');
            }
        } elseif ($action === 'reset_invite') {
            reset_group_invite($pdo, $shownId);
            flash_set('New invite link and key made — the old ones no longer work.', 'success');
        } elseif ($action === 'set_permission') {
            set_member_permission($pdo, $shownId, (int)($_POST['user_id'] ?? 0), (string)($_POST['permission'] ?? 'edit'));
            flash_set('Updated.', 'success');
        } elseif ($action === 'remove_member') {
            $memberId = (int)($_POST['user_id'] ?? 0);
            if ($memberId !== $userId) {
                remove_group_member($pdo, $shownId, $memberId);
                flash_set('Removed from the group.', 'success');
            }
        } elseif ($action === 'delete_group') {
            if (trim((string)($_POST['confirm_name'] ?? '')) !== $shown['name']) {
                flash_set('Type the group\'s exact name to delete it.', 'error');
                redirect('/groups/' . $shownId);
            }
            delete_group($pdo, $shownId);
            flash_set('Group "' . $shown['name'] . '" and everything in it were deleted.', 'success');
            redirect('/groups');
        }
        redirect('/groups/' . $shownId);
    }

    $members = list_group_members($pdo, $shownId);
    ob_start();
    ?>
    <div class="card-head">
      <div class="card-body">
        <h1><?= h($shown['name']) ?></h1>
        <p class="meta"><?= count($members) ?> member<?= count($members) === 1 ? '' : 's' ?> · <?= $isOwner ? 'you own this group' : ($shown['permission'] === 'view' ? 'you can view' : 'you can edit') ?></p>
      </div>
      <?php if ($shownId !== $groupId): ?>
      <form method="post" action="/switch-group" class="inline-form">
        <input type="hidden" name="group_id" value="<?= $shownId ?>">
        <button type="submit" class="secondary">Open this group</button>
      </form>
      <?php endif; ?>
    </div>

    <?php if ($shown['permission'] === 'edit'): ?>
    <h2>Invite people</h2>
    <div class="qr-block">
      <div class="qr-image"><?= qrcode_svg_markup($inviteUrl, 4) ?></div>
      <div class="qr-info">
        <p class="meta">Send this link, or let them scan the code — they'll join after logging in or creating an account.</p>
        <p class="meta"><code id="invite-link"><?= h($inviteUrl) ?></code></p>
        <div class="row-actions">
          <button type="button" class="secondary copy-btn" data-copy-target="invite-link"><?= icon('copy', 14) ?><span class="copy-btn-label">Copy link</span></button>
          <a class="btn secondary" href="/groups/<?= $shownId ?>/invite.svg" download><?= icon('download', 14) ?>SVG</a>
          <a class="btn secondary" href="/groups/<?= $shownId ?>/invite.png" download><?= icon('download', 14) ?>PNG</a>
        </div>
        <p class="copy-status meta" role="status" aria-live="polite"></p>
        <p class="meta">Or tell them to choose <strong>Join a group</strong> under Groups and type:</p>
        <dl class="join-details">
          <dt>Name</dt><dd><code><?= h($shown['name']) ?></code></dd>
          <dt>Key</dt><dd><code class="join-key"><?= h(format_join_key($shown['join_key'])) ?></code></dd>
        </dl>
        <?php if ($isOwner): ?>
        <form method="post" class="inline-form" onsubmit="return confirm('Make a new link and key? The current ones will stop working.');">
          <input type="hidden" name="action" value="reset_invite">
          <button type="submit" class="btn-ghost"><?= icon('lock', 14) ?>New link and key</button>
        </form>
        <?php endif; ?>
      </div>
    </div>
    <?php endif; ?>

    <h2>Members</h2>
    <ul class="card-list">
      <?php foreach ($members as $m): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($m['username']) ?><?= (int)$m['id'] === $userId ? ' (you)' : '' ?></span>
              <span class="meta"><?= $m['role'] === 'owner' ? 'owner' : ($m['permission'] === 'edit' ? 'can edit' : 'view only') ?></span>
            </div>
            <?php if ($isOwner && $m['role'] !== 'owner'): ?>
            <details class="card-menu">
              <summary aria-label="Manage member"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="set_permission">
                  <input type="hidden" name="user_id" value="<?= (int)$m['id'] ?>">
                  <select name="permission">
                    <option value="edit" <?= $m['permission'] === 'edit' ? 'selected' : '' ?>>Can edit</option>
                    <option value="view" <?= $m['permission'] === 'view' ? 'selected' : '' ?>>View only</option>
                  </select>
                  <button type="submit" class="secondary"><?= icon('check', 14) ?>Save</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this person from the group?');">
                  <input type="hidden" name="action" value="remove_member">
                  <input type="hidden" name="user_id" value="<?= (int)$m['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Remove</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
    </ul>

    <h2>Settings</h2>
    <?php if ($isOwner): ?>
      <form method="post" class="inline-form">
        <input type="hidden" name="action" value="rename_group">
        <input type="text" name="name" value="<?= h($shown['name']) ?>" required maxlength="<?= GROUP_NAME_MAX_LENGTH ?>" aria-label="Group name">
        <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
      </form>
      <div class="card add-card danger-zone">
        <details>
          <summary><?= icon('trash', 15) ?>Delete this group</summary>
          <form method="post" class="stack-form-v">
            <input type="hidden" name="action" value="delete_group">
            <p class="meta">This deletes every place, box and item in "<?= h($shown['name']) ?>" for all its members. Type the group's name to confirm.</p>
            <label>Group name<input type="text" name="confirm_name" required autocomplete="off"></label>
            <button type="submit" class="danger">Delete group and everything in it</button>
          </form>
        </details>
      </div>
    <?php else: ?>
      <form method="post" class="inline-form" onsubmit="return confirm('Leave this group? You will lose access to everything in it.');">
        <input type="hidden" name="action" value="leave_group">
        <button type="submit" class="danger"><?= icon('log-out', 14) ?>Leave group</button>
      </form>
    <?php endif; ?>
    <?php
    layout($shown['name'], ob_get_clean(), ['Groups' => '/groups', $shown['name'] => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Routes: /add/{token} and /remove/{token} — what a box's or place's
// add-item / remove-item QR codes link to. The token is the box's or
// place's share token; you must be in its group (it becomes the active
// one). Plus /add|remove/{token}/qr.svg|png and label.svg|png.
// -------------------------------------------------------------------------
if (count($segments) >= 2 && in_array($segments[0], ['add', 'remove'], true)) {
    $qrAction = $segments[0];
    $token = $segments[1];
    $box = find_box_by_token($pdo, $token);
    $place = null;
    if ($box) {
        $stmt = $pdo->prepare('SELECT * FROM places WHERE id = ?');
        $stmt->execute([(int)$box['place_id']]);
        $place = $stmt->fetch() ?: null;
    } else {
        $place = find_place_by_token($pdo, $token);
    }
    if (!$place || $place['group_id'] === null || !find_membership($pdo, (int)$place['group_id'], $userId)) {
        render_error(404, 'This code is for a box or place that doesn\'t exist anymore, or is in a group you\'re not part of.', $nav);
    }
    $actionUrl = action_qr_url($qrAction, $token);
    $title = $box ? $box['name'] : $place['name'];
    $subtitle = ($qrAction === 'add' ? 'Scan to add an item' : 'Scan to remove an item') . ($box ? ' · ' . $place['name'] : '');

    if (count($segments) === 3 && in_array($segments[2], ['qr.svg', 'qr.png'], true)) {
        $segments[2] === 'qr.png' ? qrcode_send_png($actionUrl) : qrcode_send_svg($actionUrl);
        exit;
    }
    if (count($segments) === 3 && in_array($segments[2], ['label.svg', 'label.png'], true)) {
        require_once __DIR__ . '/includes/label.php';
        $widthMm = isset($_GET['w']) ? (float)$_GET['w'] : 50.0;
        $heightMm = isset($_GET['h']) ? (float)$_GET['h'] : 30.0;
        $dpi = isset($_GET['dpi']) ? (int)$_GET['dpi'] : 300;
        if ($segments[2] === 'label.png') {
            label_send_png($actionUrl, $title, $subtitle, $widthMm, $heightMm, $dpi);
        } else {
            label_send_svg($actionUrl, $title, $subtitle, $widthMm, $heightMm);
        }
        exit;
    }
    if (count($segments) !== 2) {
        render_error(404, 'Page not found.', $nav);
    }

    set_active_group($pdo, (int)$place['group_id']);
    $target = '/place/' . $place['slug'] . ($box ? '/' . $box['slug'] : '');
    redirect($target . '?mode=' . $qrAction . '#' . ($qrAction === 'add' ? 'add-item' : 'items'));
}

// -------------------------------------------------------------------------
// Route: /account — change your own password
// -------------------------------------------------------------------------
if ($segments === ['account']) {
    if ($method === 'POST') {
        $current = (string)($_POST['current_password'] ?? '');
        $new = (string)($_POST['new_password'] ?? '');
        $confirm = (string)($_POST['new_password_confirm'] ?? '');
        if (!password_verify($current, $currentUser['password_hash'])) {
            flash_set('Current password is wrong.', 'error');
        } elseif (strlen($new) < 8) {
            flash_set('New password must be at least 8 characters.', 'error');
        } elseif ($new !== $confirm) {
            flash_set('New passwords don\'t match.', 'error');
        } else {
            $pdo->prepare('UPDATE users SET password_hash = ? WHERE id = ?')
                ->execute([password_hash($new, PASSWORD_DEFAULT), (int)$currentUser['id']]);
            flash_set('Password updated.', 'success');
        }
        redirect('/account');
    }
    ob_start();
    ?>
    <h1>Account</h1>
    <p class="page-subtitle">Signed in as <strong><?= h($currentUser['username']) ?></strong>.</p>
    <form method="post" class="stack-form-v">
      <label>Current password<input type="password" name="current_password" required></label>
      <label>New password<input type="password" name="new_password" required minlength="8"></label>
      <label>Confirm new password<input type="password" name="new_password_confirm" required minlength="8"></label>
      <button type="submit">Change password</button>
    </form>
    <?php
    layout('Account', ob_get_clean(), ['Account' => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: home
// -------------------------------------------------------------------------
if ($segments === []) {
    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if ($action === 'create_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                create_place($pdo, $groupId, $name);
                flash_set('Place "' . $name . '" created.', 'success');
            } else {
                flash_set('Place name cannot be empty.', 'error');
            }
        } elseif ($action === 'rename_place') {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            if ($id && $name !== '') {
                $slug = unique_place_slug($pdo, $groupId, $name, $id);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ? AND group_id = ?')->execute([$name, $slug, $id, $groupId]);
                flash_set('Place renamed.', 'success');
            }
        } elseif ($action === 'delete_place') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM places WHERE id = ? AND group_id = ?')->execute([$id, $groupId]);
                flash_set('Place deleted.', 'success');
            }
        }
        redirect('/');
    }

    $stmt = $pdo->prepare(
        "SELECT places.*,
                (SELECT COUNT(*) FROM boxes WHERE boxes.place_id = places.id) AS box_count,
                (SELECT COUNT(*) FROM items JOIN boxes ON boxes.id = items.box_id WHERE boxes.place_id = places.id)
                    + (SELECT COUNT(*) FROM items WHERE items.place_id = places.id) AS item_count
         FROM places WHERE group_id = ? ORDER BY name COLLATE NOCASE"
    );
    $stmt->execute([$groupId]);
    $places = $stmt->fetchAll();

    ob_start();
    ?>
    <h1>Places</h1>
    <p class="page-subtitle">Everything in <?= h($group['name']) ?>, findable in seconds.<?php if ((int)$group['member_count'] > 1): ?> Shared by <?= (int)$group['member_count'] ?> people — <a href="/groups/<?= $groupId ?>">see who</a>.<?php elseif ($canEdit): ?> <a href="/groups/<?= $groupId ?>">Invite people</a> to share it.<?php endif; ?></p>
    <?php if (!$places): ?>
      <div class="empty-state">
        <?= icon('inbox', 28) ?>
        <p>No places yet.</p>
        <?php if ($canEdit): ?><p class="empty-hint">Add your first place below (e.g. "Garage", "Attic", "Kitchen").</p><?php endif; ?>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($places as $p): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <a class="card-title" href="/place/<?= h($p['slug']) ?>"><?= h($p['name']) ?></a>
              <span class="meta"><?= (int)$p['box_count'] ?> box(es) · <?= (int)$p['item_count'] ?> item(s)</span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage place"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_place">
                  <input type="hidden" name="id" value="<?= (int)$p['id'] ?>">
                  <input type="text" name="name" value="<?= h($p['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this place and everything inside it?');">
                  <input type="hidden" name="action" value="delete_place">
                  <input type="hidden" name="id" value="<?= (int)$p['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a place</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_place">
            <input type="text" name="name" placeholder="e.g. Garage" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php
    layout('Places', ob_get_clean(), [], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /search
// -------------------------------------------------------------------------
if ($segments === ['search']) {
    $q = trim($_GET['q'] ?? '');
    $results = [];
    if ($q !== '') {
        $like = '%' . str_replace(['\\', '%', '_'], ['\\\\', '\\%', '\\_'], $q) . '%';
        $stmt = $pdo->prepare(
            "SELECT items.id, items.name AS item_name, items.quantity,
                    boxes.name AS box_name, boxes.slug AS box_slug,
                    places.name AS place_name, places.slug AS place_slug
             FROM items
             LEFT JOIN boxes ON boxes.id = items.box_id
             JOIN places ON places.id = COALESCE(items.place_id, boxes.place_id)
             WHERE places.group_id = ? AND items.name LIKE ? ESCAPE '\\'
             ORDER BY items.name COLLATE NOCASE"
        );
        $stmt->execute([$groupId, $like]);
        $results = $stmt->fetchAll();
    }

    ob_start();
    ?>
    <h1>Search</h1>
    <form method="get" class="stack-form">
      <div class="search-wrap">
        <?= icon('search', 15) ?>
        <input type="search" name="q" value="<?= h($q) ?>" placeholder="e.g. glue" autofocus>
      </div>
      <button type="submit">Search</button>
    </form>
    <?php if ($q === ''): ?>
      <div class="empty-state">
        <?= icon('search', 28) ?>
        <p>Type something above to search across every place and box.</p>
      </div>
    <?php elseif (!$results): ?>
      <div class="empty-state">
        <?= icon('inbox', 28) ?>
        <p>No items matching "<?= h($q) ?>" were found.</p>
      </div>
    <?php else: ?>
      <p class="meta"><?= count($results) ?> result(s) for "<?= h($q) ?>"</p>
      <ul class="card-list">
        <?php foreach ($results as $r): ?>
          <li class="card">
            <div class="card-body">
              <span class="card-title"><?= h($r['item_name']) ?><?php if ((int)$r['quantity'] > 1): ?> <span class="qty-badge">×<?= (int)$r['quantity'] ?></span><?php endif; ?></span>
              <span class="meta">in
                <a href="/place/<?= h($r['place_slug']) ?>"><?= h($r['place_name']) ?></a>
                <?php if ($r['box_name'] !== null): ?>
                  <?= icon('chevron', 12) ?>
                  <a href="/place/<?= h($r['place_slug']) ?>/<?= h($r['box_slug']) ?>"><?= h($r['box_name']) ?></a>
                <?php endif; ?>
              </span>
            </div>
          </li>
        <?php endforeach; ?>
      </ul>
    <?php endif; ?>
    <?php
    layout('Search', ob_get_clean(), [], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /place/{placeSlug}
// -------------------------------------------------------------------------
if (count($segments) >= 2 && $segments[0] === 'place') {
    $placeSlug = $segments[1];
    $place = find_place_by_slug($pdo, $groupId, $placeSlug);
    if (!$place) {
        render_error(404, 'No place found for "' . $placeSlug . '".', $nav);
    }
    $placeId = (int)$place['id'];

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}/qr.svg | qr.png
    //   Downloadable QR code for the box, encoding the public read-only
    //   /view/{token} page.
    // ---------------------------------------------------------------
    if (count($segments) === 4 && in_array($segments[3], ['qr.svg', 'qr.png'], true)) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        if ($segments[3] === 'qr.png') {
            qrcode_send_png($viewUrl);
        } else {
            qrcode_send_svg($viewUrl);
        }
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}/label.svg | label.png
    //   A printable label: QR code + box name + place name + border +
    //   icons, sized for a label printer. Query params (all optional):
    //     ?w=50&h=30     label size in millimeters (default 50x30)
    //     ?dpi=300       PNG resolution only (default 300)
    // ---------------------------------------------------------------
    if (count($segments) === 4 && in_array($segments[3], ['label.svg', 'label.png'], true)) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        require_once __DIR__ . '/includes/label.php';
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        $widthMm = isset($_GET['w']) ? (float)$_GET['w'] : 50.0;
        $heightMm = isset($_GET['h']) ? (float)$_GET['h'] : 30.0;
        $dpi = isset($_GET['dpi']) ? (int)$_GET['dpi'] : 300;
        if ($segments[3] === 'label.png') {
            label_send_png($viewUrl, $box['name'], $place['name'], $widthMm, $heightMm, $dpi);
        } else {
            label_send_svg($viewUrl, $box['name'], $place['name'], $widthMm, $heightMm);
        }
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}/{boxSlug}
    // ---------------------------------------------------------------
    if (count($segments) === 3) {
        $boxSlug = $segments[2];
        $box = find_box_by_slug($pdo, $placeId, $boxSlug);
        if (!$box) {
            render_error(404, 'No box "' . $boxSlug . '" found in "' . $place['name'] . '".', $nav);
        }
        $boxId = (int)$box['id'];
        $viewUrl = base_url() . '/view/' . $box['share_token'];
        $scanKey = 'box:' . $boxId;
        $mode = item_mode();

        if ($method === 'POST') {
            require_edit($pdo);
            $action = $_POST['action'] ?? '';
            if (handle_item_action($pdo, $action, $boxId, null, $groupId)) {
                // handled — falls through to the redirect below
            } elseif (handle_scan_action($action, $scanKey)) {
                // handled — falls through to the redirect below
            } elseif ($action === 'scan_add_all') {
                $reviewItems = ocr_review_get($scanKey);
                foreach ($reviewItems as $ri) {
                    $pdo->prepare('INSERT INTO items (box_id, name, quantity) VALUES (?, ?, ?)')
                        ->execute([$boxId, $ri['name'], $ri['quantity']]);
                }
                ocr_review_clear($scanKey);
                flash_set(count($reviewItems) . ' item(s) added.', 'success');
            } elseif ($action === 'rename_box') {
                $name = trim($_POST['name'] ?? '');
                if ($name !== '') {
                    $slug = unique_box_slug($pdo, $placeId, $name, $boxId);
                    $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $boxId]);
                    flash_set('Box renamed.', 'success');
                    redirect('/place/' . $placeSlug . '/' . $slug);
                }
            } elseif ($action === 'move_box') {
                $newPlaceId = (int)($_POST['place_id'] ?? 0);
                $newSlug = move_box_to($pdo, $boxId, $groupId, $newPlaceId);
                if ($newSlug !== null) {
                    $newPlaceStmt = $pdo->prepare('SELECT slug FROM places WHERE id = ?');
                    $newPlaceStmt->execute([$newPlaceId]);
                    $newPlaceSlug = $newPlaceStmt->fetchColumn();
                    flash_set('Box moved.', 'success');
                    redirect('/place/' . $newPlaceSlug . '/' . $newSlug);
                }
                flash_set('Couldn\'t move that box — pick a valid place.', 'error');
            } elseif ($action === 'delete_box') {
                $pdo->prepare('DELETE FROM boxes WHERE id = ?')->execute([$boxId]);
                flash_set('Box deleted.', 'success');
                redirect('/place/' . $placeSlug);
            }
            redirect('/place/' . $placeSlug . '/' . $boxSlug . item_mode_query($mode));
        }

        $stmt = $pdo->prepare('SELECT * FROM items WHERE box_id = ? ORDER BY name COLLATE NOCASE');
        $stmt->execute([$boxId]);
        $items = $stmt->fetchAll();
        $pendingBarcode = pending_barcode_take();
        $pendingName = pending_name_take();
        $reviewItems = ocr_review_get($scanKey);
        $moveDestinations = $canEdit ? list_move_destinations($pdo, $groupId) : [];

        ob_start();
        ?>
        <div class="card-head">
          <div class="card-body">
            <h1><?= h($box['name']) ?></h1>
            <p class="meta">Box in <a href="/place/<?= h($placeSlug) ?>"><?= h($place['name']) ?></a> · permalink <code>/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?></code></p>
          </div>
          <?php if ($canEdit): ?>
          <details class="card-menu">
            <summary aria-label="Manage box"><?= icon('dots', 18) ?></summary>
            <div class="card-menu-body">
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="rename_box">
                <input type="text" name="name" value="<?= h($box['name']) ?>" required>
                <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
              </form>
              <?php if (count($moveDestinations) > 1): ?>
              <form method="post" class="inline-form">
                <input type="hidden" name="action" value="move_box">
                <select name="place_id" aria-label="Move to place">
                  <?php foreach ($moveDestinations as $p): ?>
                    <option value="<?= (int)$p['id'] ?>" <?= ((int)$p['id'] === $placeId) ? 'selected' : '' ?>><?= h($p['name']) ?></option>
                  <?php endforeach; ?>
                </select>
                <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
              </form>
              <?php endif; ?>
              <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                <input type="hidden" name="action" value="delete_box">
                <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete box</button>
              </form>
            </div>
          </details>
          <?php endif; ?>
        </div>

        <div class="qr-block">
          <div class="qr-image"><?= qrcode_svg_markup($viewUrl, 4) ?></div>
          <div class="qr-info">
            <p class="meta">Scan this to see the box's contents — a read-only page, no login needed, handy for a printed label on the box itself.</p>
            <p class="meta"><code><?= h($viewUrl) ?></code></p>
            <div class="row-actions">
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.svg" download><?= icon('download', 14) ?>SVG</a>
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/qr.png" download><?= icon('download', 14) ?>PNG</a>
              <a class="btn secondary" href="/api/boxes/<?= $boxId ?>/contents" target="_blank" rel="noopener"><?= icon('external', 14) ?>Open JSON</a>
            </div>
          </div>
        </div>

        <h2>Printable label</h2>
        <div class="label-block">
          <div class="label-preview">
            <img src="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.svg" alt="Printable label for <?= h($box['name']) ?>" width="300" height="180">
          </div>
          <div class="label-info">
            <p class="meta">A ready-to-print label: QR code, box name, place name, a border, and a couple of icons. Default size is 50×30mm — add <code>?w=</code>/<code>?h=</code> (millimeters) to the link for a different label size, or <code>?dpi=</code> for the PNG's resolution.</p>
            <div class="row-actions">
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.svg" download><?= icon('download', 14) ?>SVG</a>
              <a class="btn secondary" href="/place/<?= h($placeSlug) ?>/<?= h($box['slug']) ?>/label.png" download><?= icon('download', 14) ?>PNG</a>
            </div>
          </div>
        </div>

        <?php if ($mode === ''): ?><?= render_photo_scan_section($scanKey, $reviewItems, $canEdit) ?><?php endif; ?>
        <?= render_items_section($items, $pendingBarcode, $pendingName, $canEdit, $moveDestinations, $place['name'] . ' / ' . $box['name'], $mode, '/place/' . $placeSlug . '/' . $box['slug']) ?>
        <?= render_action_qr_section((string)$box['share_token'], 'box', $canEdit) ?>
        <?php
        layout($box['name'], ob_get_clean(), [$place['name'] => '/place/' . $placeSlug, $box['name'] => null], $nav);
        exit;
    }

    // ---------------------------------------------------------------
    // Route: /place/{placeSlug}  (list of boxes)
    // ---------------------------------------------------------------
    $scanKey = 'place:' . $placeId;
    $mode = item_mode();

    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if (handle_item_action($pdo, $action, null, $placeId, $groupId)) {
            // handled — falls through to the redirect below
        } elseif (handle_scan_action($action, $scanKey)) {
            // handled — falls through to the redirect below
        } elseif ($action === 'scan_add_all') {
            $reviewItems = ocr_review_get($scanKey);
            foreach ($reviewItems as $ri) {
                $pdo->prepare('INSERT INTO items (place_id, name, quantity) VALUES (?, ?, ?)')
                    ->execute([$placeId, $ri['name'], $ri['quantity']]);
            }
            ocr_review_clear($scanKey);
            flash_set(count($reviewItems) . ' item(s) added.', 'success');
        } elseif ($action === 'create_box') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_box_slug($pdo, $placeId, $name);
                $pdo->prepare('INSERT INTO boxes (place_id, name, slug, share_token) VALUES (?, ?, ?, ?)')
                    ->execute([$placeId, $name, $slug, new_share_token()]);
                flash_set('Box "' . $name . '" created.', 'success');
            }
        } elseif ($action === 'rename_box') {
            $id = (int)($_POST['id'] ?? 0);
            $name = trim($_POST['name'] ?? '');
            if ($id && $name !== '') {
                $slug = unique_box_slug($pdo, $placeId, $name, $id);
                $pdo->prepare('UPDATE boxes SET name = ?, slug = ? WHERE id = ? AND place_id = ?')->execute([$name, $slug, $id, $placeId]);
                flash_set('Box renamed.', 'success');
            }
        } elseif ($action === 'move_box') {
            $id = (int)($_POST['id'] ?? 0);
            $newPlaceId = (int)($_POST['place_id'] ?? 0);
            if ($id) {
                if (move_box_to($pdo, $id, $groupId, $newPlaceId) !== null) {
                    flash_set('Box moved.', 'success');
                } else {
                    flash_set('Couldn\'t move that box — pick a valid place.', 'error');
                }
            }
        } elseif ($action === 'delete_box') {
            $id = (int)($_POST['id'] ?? 0);
            if ($id) {
                $pdo->prepare('DELETE FROM boxes WHERE id = ? AND place_id = ?')->execute([$id, $placeId]);
                flash_set('Box deleted.', 'success');
            }
        } elseif ($action === 'rename_place') {
            $name = trim($_POST['name'] ?? '');
            if ($name !== '') {
                $slug = unique_place_slug($pdo, $groupId, $name, $placeId);
                $pdo->prepare('UPDATE places SET name = ?, slug = ? WHERE id = ?')->execute([$name, $slug, $placeId]);
                flash_set('Place renamed.', 'success');
                redirect('/place/' . $slug);
            }
        } elseif ($action === 'delete_place') {
            $pdo->prepare('DELETE FROM places WHERE id = ?')->execute([$placeId]);
            flash_set('Place deleted.', 'success');
            redirect('/');
        }
        redirect('/place/' . $placeSlug . item_mode_query($mode));
    }

    $stmt = $pdo->prepare(
        "SELECT boxes.*, (SELECT COUNT(*) FROM items WHERE items.box_id = boxes.id) AS item_count
         FROM boxes WHERE place_id = ? ORDER BY name COLLATE NOCASE"
    );
    $stmt->execute([$placeId]);
    $boxes = $stmt->fetchAll();

    $stmt = $pdo->prepare('SELECT * FROM items WHERE place_id = ? ORDER BY name COLLATE NOCASE');
    $stmt->execute([$placeId]);
    $placeItems = $stmt->fetchAll();
    $pendingBarcode = pending_barcode_take();
    $pendingName = pending_name_take();
    $reviewItems = ocr_review_get($scanKey);
    $moveDestinations = $canEdit ? list_move_destinations($pdo, $groupId) : [];

    ob_start();
    ?>
    <div class="card-head">
      <h1><?= h($place['name']) ?></h1>
      <?php if ($canEdit): ?>
      <details class="card-menu">
        <summary aria-label="Manage place"><?= icon('dots', 18) ?></summary>
        <div class="card-menu-body">
          <form method="post" class="inline-form">
            <input type="hidden" name="action" value="rename_place">
            <input type="text" name="name" value="<?= h($place['name']) ?>" required>
            <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
          </form>
          <form method="post" class="inline-form" onsubmit="return confirm('Delete this place and everything inside it?');">
            <input type="hidden" name="action" value="delete_place">
            <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete place</button>
          </form>
        </div>
      </details>
      <?php endif; ?>
    </div>

    <h2>Boxes</h2>
    <?php if (!$boxes): ?>
      <div class="empty-state">
        <?= icon('box', 28) ?>
        <p>No boxes here yet<?= $canEdit ? ' — add one below.' : '.' ?></p>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($boxes as $b): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <a class="card-title" href="/place/<?= h($placeSlug) ?>/<?= h($b['slug']) ?>"><?= h($b['name']) ?></a>
              <span class="meta"><?= (int)$b['item_count'] ?> item(s) · <code>/place/<?= h($placeSlug) ?>/<?= h($b['slug']) ?></code></span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage box"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <input type="text" name="name" value="<?= h($b['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <?php if (count($moveDestinations) > 1): ?>
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="move_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <select name="place_id" aria-label="Move to place">
                    <?php foreach ($moveDestinations as $p): ?>
                      <option value="<?= (int)$p['id'] ?>" <?= ((int)$p['id'] === $placeId) ? 'selected' : '' ?>><?= h($p['name']) ?></option>
                    <?php endforeach; ?>
                  </select>
                  <button type="submit" class="secondary"><?= icon('move', 14) ?>Move</button>
                </form>
                <?php endif; ?>
                <form method="post" class="inline-form" onsubmit="return confirm('Delete this box and all its items?');">
                  <input type="hidden" name="action" value="delete_box">
                  <input type="hidden" name="id" value="<?= (int)$b['id'] ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Delete</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a box</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_box">
            <input type="text" name="name" placeholder="e.g. Tools bin 2" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>

    <?php if ($canEdit || $placeItems): ?>
    <p class="section-note">Items below are loose in <?= h($place['name']) ?> itself, not inside any box.</p>
    <?php endif; ?>
    <?php if ($mode === ''): ?><?= render_photo_scan_section($scanKey, $reviewItems, $canEdit) ?><?php endif; ?>
    <?= render_items_section($placeItems, $pendingBarcode, $pendingName, $canEdit, $moveDestinations, (string)$place['name'], $mode, '/place/' . $placeSlug) ?>
    <?= render_action_qr_section((string)$place['share_token'], 'place', $canEdit) ?>
    <?php
    layout($place['name'], ob_get_clean(), [$place['name'] => null], $nav);
    exit;
}

// -------------------------------------------------------------------------
// Route: /barcodes — the barcode -> item register (shared across every
// account — a barcode identifies a real-world product, not personal data).
// -------------------------------------------------------------------------
if ($segments === ['barcodes']) {
    if ($method === 'POST') {
        require_edit($pdo);
        $action = $_POST['action'] ?? '';
        if ($action === 'create_barcode') {
            $barcode = trim($_POST['barcode'] ?? '');
            $name = trim($_POST['name'] ?? '');
            if ($barcode !== '' && $name !== '') {
                remember_barcode($pdo, $barcode, $name);
                flash_set('Barcode linked to "' . $name . '".', 'success');
            } else {
                flash_set('A barcode and an item name are both required.', 'error');
            }
        } elseif ($action === 'rename_barcode') {
            $barcode = $_POST['barcode'] ?? '';
            $name = trim($_POST['name'] ?? '');
            if ($barcode !== '' && $name !== '') {
                $pdo->prepare('UPDATE barcode_items SET name = ? WHERE barcode = ?')->execute([$name, $barcode]);
                flash_set('Barcode updated.', 'success');
            }
        } elseif ($action === 'delete_barcode') {
            $barcode = $_POST['barcode'] ?? '';
            if ($barcode !== '') {
                $pdo->prepare('DELETE FROM barcode_items WHERE barcode = ?')->execute([$barcode]);
                flash_set('Barcode association removed.', 'success');
            }
        }
        redirect('/barcodes');
    }

    $barcodes = $pdo->query('SELECT * FROM barcode_items ORDER BY name COLLATE NOCASE')->fetchAll();

    ob_start();
    ?>
    <h1>Barcodes</h1>
    <p class="page-subtitle">Scan a barcode once while adding an item, and thingsFinder remembers what it is from then on — shared by everyone who uses this app.</p>
    <?php if (!$barcodes): ?>
      <div class="empty-state">
        <?= icon('barcode', 28) ?>
        <p>No barcodes registered yet.</p>
        <?php if ($canEdit): ?><p class="empty-hint">Scan one while adding an item, or add an association manually below.</p><?php endif; ?>
      </div>
    <?php endif; ?>
    <ul class="card-list">
      <?php foreach ($barcodes as $bc): ?>
        <li class="card">
          <div class="card-head">
            <div class="card-body">
              <span class="card-title"><?= h($bc['name']) ?></span>
              <span class="meta"><code><?= h($bc['barcode']) ?></code></span>
            </div>
            <?php if ($canEdit): ?>
            <details class="card-menu">
              <summary aria-label="Manage barcode"><?= icon('dots', 16) ?></summary>
              <div class="card-menu-body">
                <form method="post" class="inline-form">
                  <input type="hidden" name="action" value="rename_barcode">
                  <input type="hidden" name="barcode" value="<?= h($bc['barcode']) ?>">
                  <input type="text" name="name" value="<?= h($bc['name']) ?>" required>
                  <button type="submit" class="secondary"><?= icon('edit', 14) ?>Rename</button>
                </form>
                <form method="post" class="inline-form" onsubmit="return confirm('Remove this association? The barcode will need to be scanned and named again to relearn it.');">
                  <input type="hidden" name="action" value="delete_barcode">
                  <input type="hidden" name="barcode" value="<?= h($bc['barcode']) ?>">
                  <button type="submit" class="danger"><?= icon('trash', 14) ?>Remove</button>
                </form>
              </div>
            </details>
            <?php endif; ?>
          </div>
        </li>
      <?php endforeach; ?>
      <?php if ($canEdit): ?>
      <li class="card add-card">
        <details>
          <summary><?= icon('plus', 15) ?>Add a barcode</summary>
          <form method="post">
            <input type="hidden" name="action" value="create_barcode">
            <input type="text" name="barcode" placeholder="Barcode number" required inputmode="numeric">
            <input type="text" name="name" placeholder="Item name, e.g. Hot glue gun" required>
            <button type="submit">Add</button>
          </form>
        </details>
      </li>
      <?php endif; ?>
    </ul>
    <?php
    layout('Barcodes', ob_get_clean(), [], $nav);
    exit;
}

render_error(404, 'Page not found.', $nav);
